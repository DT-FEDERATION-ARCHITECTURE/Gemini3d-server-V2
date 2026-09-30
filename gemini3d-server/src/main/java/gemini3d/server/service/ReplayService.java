package gemini3d.server.service;

import gemini3d.server.model.InclusionStepMessage;
import gemini3d.server.websocket.InclusionWebSocketHandler;
import gemini3d.trace.emulator.Gemini3DEmulator;
import gemini3d.trace.emulator.Reading;
import gemini3d.trace.fifo.CircularFIFO;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.io.BufferedReader;
import java.io.FileReader;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Pure trace replay: streams a CSV's rows over the WebSocket, with no
 * automaton and no monitoring federation involved.
 *
 * Why this exists separately from {@link InclusionService}: that service is
 * the runtime-verification pipeline (Emulator -> TraceSLI -> AutomatonSTR ->
 * Verdict) and legitimately requires a loaded automaton. Consumers that only
 * need the sensor stream -- the 3D digital-twin view, which compares a printed
 * part against its G-code -- were being forced to load an automaton they never
 * use. Worse, {@code AutomatonService} holds a single server-wide
 * {@code currentAutomaton}, so doing that would clobber whatever another
 * client had loaded.
 *
 * This path touches no shared state: each replay owns its own emulator, FIFO
 * and threads, and pushes over the existing per-session WebSocket handler.
 *
 * <h2>Cadence : horodatages de la trace, pas un delai fixe</h2>
 *
 * Historiquement le rejeu utilisait un {@code periodMs} fixe, applique par
 * {@code Gemini3DEmulator} entre deux lignes. Cela ne peut pas convenir a
 * l'ensemble des traces, parce qu'elles n'ont pas du tout le meme
 * echantillonnage :
 *
 * <pre>
 *   Module2/logEgm   79 288 lignes   319,7 s    cadence 4,03 ms, tres reguliere
 *   OMEGA OUTILLAGE   7 925 lignes  3 984,1 s   cadence mediane 504 ms,
 *                                               mais variant de 4 ms a 1609 ms
 * </pre>
 *
 * Avec {@code periodMs = 4}, Module2 tombait juste par coincidence (sa cadence
 * reelle est justement de 4 ms) alors qu'OMEGA etait rejoue 124 fois trop vite.
 * Avec {@code periodMs = 500}, OMEGA retrouvait la bonne duree totale mais
 * espacait uniformement des points dont l'ecart reel varie d'un facteur 400.
 *
 * Desormais la cadence suit l'horodatage de chaque ligne, divise par un facteur
 * {@code speed} (secondes de trace jouees par seconde reelle). Le meme reglage
 * donne donc un resultat juste sur n'importe quel fichier.
 *
 * <h2>Plus aucune ligne perdue</h2>
 *
 * {@link CircularFIFO#write} ne bloque jamais : quand le tampon est plein, la
 * plus ancienne mesure est ecrasee. Le producteur allant plus vite que
 * l'envoi WebSocket, des lignes disparaissaient silencieusement du rejeu.
 * {@link PacedFifo} attend qu'une place se libere avant d'ecrire, ce qui
 * supprime ces pertes sans rien changer a la bibliotheque partagee.
 */
@Service
public class ReplayService {

    private static final Logger log = LoggerFactory.getLogger(ReplayService.class);
    private static final int FIFO_CAPACITY = 256;

    /**
     * Cadence de repli quand la trace ne porte aucune colonne de temps
     * exploitable. Volontairement rapide plutot que lente : un rejeu trop
     * rapide reste lisible, un rejeu fige passe pour un plantage.
     */
    private static final long FALLBACK_PERIOD_MS = 20;

    private final TraceService traceService;
    private final InclusionWebSocketHandler webSocketHandler;

    private final Map<String, ReplaySession> sessions = new ConcurrentHashMap<>();

    public ReplayService(TraceService traceService, InclusionWebSocketHandler webSocketHandler) {
        this.traceService = traceService;
        this.webSocketHandler = webSocketHandler;
    }

    /** Live state of one replay. */
    public static final class ReplaySession {
        public final String id;
        public final String traceFile;
        public final long periodMs;
        /** Secondes de trace jouees par seconde reelle. 0 = aucune attente. */
        public final double speed;
        /** Duree reelle de la trace, mesuree au demarrage. 0 si indeterminable. */
        public volatile double traceDurationSec;
        volatile Thread producer;
        volatile Thread consumer;
        volatile CircularFIFO<Reading> fifo;
        volatile boolean stopped;
        volatile int steps;
        volatile String status = "RUNNING";

        ReplaySession(String id, String traceFile, double speed, long periodMs) {
            this.id = id;
            this.traceFile = traceFile;
            this.speed = speed;
            this.periodMs = periodMs;
        }

        /** Steps emitted so far. Read-only from outside this package. */
        public int getSteps() { return steps; }

        /** RUNNING | FINISHED | STOPPED | ERROR. Read-only from outside this package. */
        public String getStatus() { return status; }

        /** Duree de rejeu attendue, en secondes. 0 si la duree de trace est inconnue. */
        public double getEstimatedReplaySec() {
            return (speed > 0 && traceDurationSec > 0) ? traceDurationSec / speed : 0.0;
        }

        public Map<String, Object> toStatusMap() {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("sessionId", id);
            m.put("traceFile", traceFile);
            m.put("speed", speed);
            m.put("periodMs", periodMs);
            m.put("traceDurationSec", traceDurationSec);
            m.put("estimatedReplaySec", getEstimatedReplaySec());
            m.put("steps", steps);
            m.put("status", status);
            return m;
        }
    }

    /**
     * Demarre un rejeu cadence sur les horodatages de la trace.
     *
     * @param traceFile nom d'un CSV connu de {@link TraceService}
     * @param speed     secondes de trace jouees par seconde reelle
     *                  (1 = temps reel, 30 = trente fois plus vite,
     *                  0 ou moins = aucune attente)
     * @throws IllegalArgumentException si la trace n'existe pas
     */
    public ReplaySession start(String traceFile, double speed) {
        return start(traceFile, speed, 0L);
    }

    /**
     * Demarre un rejeu a cadence fixe -- ancien comportement, conserve pour
     * les appelants qui envoient encore {@code periodMs}.
     *
     * @param periodMs delai entre deux emissions ; 0 = pleine vitesse
     */
    public ReplaySession startFixedPeriod(String traceFile, long periodMs) {
        return start(traceFile, 0.0, periodMs);
    }

    private ReplaySession start(String traceFile, double speed, long fixedPeriodMs) {
        Path csvPath = traceService.resolveTrace(traceFile); // throws if missing

        String sessionId = UUID.randomUUID().toString().substring(0, 8);
        ReplaySession session = new ReplaySession(sessionId, traceFile, speed, fixedPeriodMs);

        // Mesure indicative, uniquement affichee : le rejeu fonctionne
        // exactement de la meme facon si elle echoue.
        session.traceDurationSec = scanDurationSec(csvPath);

        // Cadence sur les horodatages : l'emulateur produit a pleine vitesse et
        // c'est la FIFO qui retient le producteur, ligne par ligne.
        // Cadence fixe : ancien chemin, l'emulateur dort lui-meme.
        boolean paced = speed > 0 || fixedPeriodMs <= 0;
        CircularFIFO<Reading> fifo = paced
                ? new PacedFifo(FIFO_CAPACITY, speed)
                : new CircularFIFO<>(FIFO_CAPACITY);
        session.fifo = fifo;

        long emulatorPeriod = paced ? 0L : fixedPeriodMs;
        Gemini3DEmulator emulator =
                new Gemini3DEmulator(csvPath.toString(), fifo, emulatorPeriod, new Object());
        emulator.setShowTracking(false);

        Thread producer = new Thread(emulator, "replay-producer-" + sessionId);
        producer.setDaemon(true);
        session.producer = producer;

        Thread consumer = new Thread(() -> consume(session), "replay-consumer-" + sessionId);
        consumer.setDaemon(true);
        session.consumer = consumer;

        sessions.put(sessionId, session);
        producer.start();
        consumer.start();

        log.info("Replay started: session={} trace={} speed=x{} traceDuration={}s estimated={}s",
                sessionId, traceFile, speed, session.traceDurationSec, session.getEstimatedReplaySec());
        return session;
    }

    /** Drains the FIFO and pushes each reading to the session's WebSocket watchers. */
    private void consume(ReplaySession session) {
        TimeResolver time = new TimeResolver();
        int step = 0;

        try {
            while (!session.stopped) {
                Reading reading = session.fifo.read();
                if (reading == null || reading.isEndOfStream()) break;

                step++;
                session.steps = step;

                Map<String, Object> measurement = reading.getValues();
                double tSeconds = time.resolve(measurement, step, FALLBACK_PERIOD_MS);

                // Confidence is uniform for now; sensor uncertainty is a later feature.
                Map<String, Integer> confidence = new LinkedHashMap<>();
                for (String key : measurement.keySet()) confidence.put(key, 100);

                // Same envelope as the inclusion path so existing clients parse it
                // unchanged -- the automaton-specific fields are simply absent.
                webSocketHandler.pushMessage(session.id, InclusionStepMessage.step(
                        step, tSeconds,
                        measurement, confidence,
                        null, null,
                        null, List.of(),
                        "REPLAY", null,
                        Map.of(), 0,
                        null, Map.of()));
            }

            if (!session.stopped) {
                session.status = "FINISHED";
                webSocketHandler.pushMessage(session.id,
                        InclusionStepMessage.finished(session.steps, session.steps, 0, Map.of()));
                log.info("Replay finished: session={} steps={}", session.id, session.steps);
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            log.info("Replay interrupted: session={}", session.id);
        } catch (Exception e) {
            session.status = "ERROR";
            log.error("Replay failed: session={}", session.id, e);
            webSocketHandler.pushMessage(session.id,
                    InclusionStepMessage.error("Replay failed: " + e.getMessage()));
        } finally {
            if (session.status.equals("RUNNING")) session.status = "STOPPED";
        }
    }

    public void stop(String sessionId) {
        ReplaySession s = sessions.get(sessionId);
        if (s == null) return;
        s.stopped = true;
        s.status = "STOPPED";
        if (s.fifo instanceof PacedFifo p) p.abort();
        if (s.fifo != null) s.fifo.close();
        if (s.producer != null) s.producer.interrupt();
        if (s.consumer != null) s.consumer.interrupt();
        log.info("Replay stopped: session={} after {} steps", sessionId, s.steps);
    }

    public Optional<ReplaySession> getSession(String sessionId) {
        return Optional.ofNullable(sessions.get(sessionId));
    }

    // ------------------------------------------------------------------------
    // Cadence
    // ------------------------------------------------------------------------

    /**
     * FIFO qui retient le producteur au lieu de le laisser courir.
     *
     * Deux retenues distinctes, toutes deux necessaires :
     *
     * <ol>
     *   <li><b>Horodatage.</b> Chaque mesure n'est ecrite qu'a l'instant ou elle
     *       est due, c'est-a-dire {@code t / speed} apres le debut du rejeu. La
     *       reference est un instant absolu pris au demarrage, et non le point
     *       precedent : sur une trace de 66 minutes, cumuler des attentes
     *       relatives ferait deriver le rejeu de plusieurs minutes, parce que
     *       {@link Thread#sleep} arrondit toujours vers le haut.</li>
     *   <li><b>Place disponible.</b> {@link CircularFIFO} ecrase la mesure la
     *       plus ancienne quand elle est pleine, sans rien signaler. On attend
     *       donc qu'une place se libere. C'est ce qui rend le mode « sans
     *       attente » utilisable : le rejeu va alors aussi vite que l'envoi
     *       WebSocket le permet, mais aucune ligne n'est perdue.</li>
     * </ol>
     *
     * Ecrire cette logique ici plutot que dans {@code Gemini3DEmulator} evite
     * de toucher la bibliotheque partagee, dont depend aussi la chaine de
     * verification a l'execution.
     */
    private static final class PacedFifo extends CircularFIFO<Reading> {
        private final double speed;
        private final TimeResolver time = new TimeResolver();
        private final int capacity;

        private long startNanos = 0L;
        private int index = 0;
        private volatile boolean aborted = false;

        PacedFifo(int capacity, double speed) {
            super(capacity);
            this.capacity = capacity;
            this.speed = speed;
        }

        void abort() { aborted = true; }

        @Override
        public void write(Reading item) {
            if (aborted) return;

            if (speed > 0) {
                index++;
                double t = time.resolve(item.getValues(), index, FALLBACK_PERIOD_MS);
                if (startNanos == 0L) startNanos = System.nanoTime();
                long dueNanos = startNanos + (long) ((t / speed) * 1_000_000_000d);
                if (!sleepUntil(dueNanos)) return;
            }

            // Ne jamais ecraser une mesure que le consommateur n'a pas encore lue.
            while (!aborted && size() >= capacity) {
                try {
                    Thread.sleep(1L);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    aborted = true;
                    return;
                }
            }
            if (aborted) return;

            super.write(item);
        }

        /** @return false si le rejeu a ete interrompu pendant l'attente. */
        private boolean sleepUntil(long dueNanos) {
            while (!aborted) {
                long remaining = dueNanos - System.nanoTime();
                if (remaining <= 0L) return true;
                // Tranches bornees : un arret demande pendant une longue pause
                // de la trace (jusqu'a 1,6 s sur OMEGA) est pris en compte vite.
                long chunkMs = Math.min(remaining / 1_000_000L, 50L);
                try {
                    if (chunkMs > 0L) Thread.sleep(chunkMs);
                    else Thread.sleep(0L, (int) Math.min(remaining, 999_999L));
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    aborted = true;
                    return false;
                }
            }
            return false;
        }
    }

    // ------------------------------------------------------------------------
    // Duree de la trace (indicative)
    // ------------------------------------------------------------------------

    /**
     * Lit le premier et le dernier horodatage exploitables du fichier.
     *
     * Sert uniquement a afficher « rejeu estime a X » dans l'interface. Toute
     * erreur renvoie 0 et le rejeu se deroule normalement : cette mesure ne
     * doit jamais pouvoir empecher un demarrage ni modifier la cadence.
     */
    private static double scanDurationSec(Path csvPath) {
        try (BufferedReader br = new BufferedReader(new FileReader(csvPath.toFile()))) {
            String header = br.readLine();
            if (header == null) return 0.0;

            char delim = detectDelimiter(header);
            String[] cols = header.split(String.valueOf(delim), -1);
            for (int i = 0; i < cols.length; i++) cols[i] = cols[i].trim();

            TimeResolver resolver = new TimeResolver();
            Double first = null, last = null;
            String line;
            while ((line = br.readLine()) != null) {
                line = line.trim();
                if (line.isEmpty()) continue;
                String[] v = line.split(String.valueOf(delim), -1);
                Map<String, Object> row = new LinkedHashMap<>();
                for (int i = 0; i < cols.length && i < v.length; i++) row.put(cols[i], v[i].trim());
                Double t = resolver.extract(row);
                if (t == null) continue;
                if (first == null) first = t;
                last = t;
            }
            if (first == null || last == null) return 0.0;
            double d = last - first;
            return d > 0 ? d : 0.0;
        } catch (Exception e) {
            log.warn("Trace duration not measurable for {}: {}", csvPath, e.toString());
            return 0.0;
        }
    }

    /** Memes regles que {@code Gemini3DEmulator}, pour lire le meme fichier pareil. */
    private static char detectDelimiter(String headerLine) {
        int s = 0, c = 0, t = 0;
        for (char ch : headerLine.toCharArray()) {
            if (ch == ';') s++;
            else if (ch == ',') c++;
            else if (ch == '\t') t++;
        }
        if (t >= s && t >= c) return '\t';
        if (s >= c) return ';';
        return ',';
    }

    /**
     * Derives a trace-time value from whatever time columns the CSV actually
     * has. The two real sample files disagree (one carries {@code time_delta}
     * as a duration string, the other {@code TimeSec}/{@code TimeUsec}), so
     * this resolves per-file rather than assuming a fixed schema, and falls
     * back to the emission period when there is no usable time column.
     */
    private static final class TimeResolver {
        private Double origin;

        double resolve(Map<String, Object> m, int step, long periodMs) {
            Double raw = extract(m);
            if (raw == null) return step * (periodMs / 1000.0);
            if (origin == null) origin = raw;
            return raw - origin;
        }

        private Double extract(Map<String, Object> m) {
            Double sec = null, usec = null;
            for (Map.Entry<String, Object> e : m.entrySet()) {
                String k = e.getKey().trim().toLowerCase();
                Object v = e.getValue();
                switch (k) {
                    case "tseconds", "t_seconds" -> { Double d = num(v); if (d != null) return d; }
                    case "timesec" -> sec = num(v);
                    case "timeusec" -> usec = num(v);
                    case "tm" -> { Double d = num(v); if (d != null) return d / 1000.0; }
                    case "time_delta", "timedelta" -> { Double d = duration(v); if (d != null) return d; }
                    default -> { }
                }
            }
            if (sec != null) return sec + (usec != null ? usec / 1_000_000.0 : 0.0);
            return null;
        }

        private static Double num(Object v) {
            if (v == null) return null;
            if (v instanceof Number n) return n.doubleValue();
            try {
                return Double.parseDouble(v.toString().trim().replace(',', '.'));
            } catch (NumberFormatException e) {
                return null;
            }
        }

        /**
         * Parses "H:MM:SS.ffffff" / "MM:SS.ff", et la forme pandas
         * "0 days 00:00:04.123456" qu'utilise egm.csv.
         *
         * Le prefixe « N days » faisait echouer le parsing, donc la colonne
         * time_delta etait ignoree et la trace retombait sur la cadence de
         * repli. Sans consequence tant que la cadence etait fixe ; determinant
         * maintenant que le temps du fichier pilote le rejeu.
         */
        private static Double duration(Object v) {
            if (v == null) return null;
            String s = v.toString().trim();
            if (s.isEmpty()) return null;

            double days = 0;
            int d = s.toLowerCase().indexOf("day");
            if (d >= 0) {
                try {
                    days = Double.parseDouble(s.substring(0, d).trim().replace(',', '.'));
                } catch (NumberFormatException e) {
                    return null;
                }
                int sp = s.indexOf(' ', d);
                s = sp >= 0 ? s.substring(sp + 1).trim() : "";
                if (s.isEmpty()) return days * 86400.0;
            }

            try {
                String[] parts = s.split(":");
                double total = 0;
                for (String p : parts) total = total * 60 + Double.parseDouble(p.replace(',', '.'));
                return days * 86400.0 + total;
            } catch (NumberFormatException e) {
                return days > 0 ? days * 86400.0 : num(v);
            }
        }
    }
}