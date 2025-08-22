// Contiene il metodo 'main' rifattorizzato E tutti i metodi estratti.
/**
 * AZIONE DI REFACTORING (Extract Class):
 * È stata creata una nuova classe 'BenchmarkConfig' per incapsulare tutti i parametri
 * del benchmark. Questo riduce il numero di variabili locali nel metodo 'main' e raggruppa
 * logicamente la configurazione in un unico oggetto, migliorando la coesione.
 */
private static class BenchmarkConfig {
    final long runningTime, timeout;
    final String servers, coordinationZnode, latencyFile;
    final int entrysize, ledgers, ensemble, quorum, ackQuorum, throttle, sendLimit;
    final boolean skipWarmup;
    final byte[] passwd, data;
    final ClientConfiguration clientConf;

    BenchmarkConfig(CommandLine cmd) {
        this.runningTime = Long.parseLong(cmd.getOptionValue("time", "60"));
        this.servers = cmd.getOptionValue("zookeeper", "localhost:2181");
        this.entrysize = Integer.parseInt(cmd.getOptionValue("entrysize", "1024"));
        this.ledgers = Integer.parseInt(cmd.getOptionValue("ledgers", "1"));
        this.ensemble = Integer.parseInt(cmd.getOptionValue("ensemble", "3"));
        this.quorum = Integer.parseInt(cmd.getOptionValue("quorum", "2"));
        this.ackQuorum = Integer.parseInt(cmd.getOptionValue("ackQuorum", String.valueOf(this.quorum)));
        this.throttle = Integer.parseInt(cmd.getOptionValue("throttle", "10000"));
        this.sendLimit = Integer.parseInt(cmd.getOptionValue("sendlimit", "20000000"));
        final int sockTimeout = Integer.parseInt(cmd.getOptionValue("sockettimeout", "5"));
        this.coordinationZnode = cmd.getOptionValue("coordnode");
        this.passwd = cmd.getOptionValue("password", "benchPasswd").getBytes();
        this.latencyFile = cmd.getOptionValue("latencyFile", "latencyDump.dat");
        this.timeout = Long.parseLong(cmd.getOptionValue("timeout", "0")) * 1000;
        this.skipWarmup = cmd.hasOption("skipwarmup");
        this.data = new byte[this.entrysize];
        Arrays.fill(this.data, (byte) 'x');
        this.clientConf = new ClientConfiguration();
        this.clientConf.setThrottleValue(this.throttle).setReadTimeout(sockTimeout).setZkServers(this.servers);
    }
}

/**
 * AZIONE DI REFACTORING (Decomposition):
 * Il metodo 'main' originale (AFMethod) è stato decomposto in più metodi privati,
 * ognuno con una singola responsabilità. Il nuovo 'main' (AFMethod2) ora orchestra
 * le chiamate a questi metodi, agendo come un coordinatore. Questo riduce drasticamente
 * il suo LOC, la sua complessità ciclomatica e la sua profondità di annidamento,
 * risolvendo lo smell "Long Method".
 */
public static void main(String[] args)
        throws KeeperException, IOException, InterruptedException, ParseException, BKException {

    BenchmarkConfig config = parseArguments(args);
    if (config == null) {
        return;
    }
    logParameters(config);
    Timer timeouter = setupTimeout(config);

    if (!config.skipWarmup) {
        runWarmup(config);
    }

    BenchThroughputLatency bench = new BenchThroughputLatency(config.ensemble, config.quorum, config.ackQuorum,
            config.passwd, config.ledgers, config.sendLimit, config.clientConf);
    bench.setEntryData(config.data);
    Thread benchmarkThread = new Thread(bench);

    ZooKeeper zk = setupZooKeeperCoordination(config);

    benchmarkThread.start();
    Thread.sleep(config.runningTime * 1000);
    benchmarkThread.interrupt();
    benchmarkThread.join();

    processAndSaveResults(bench, zk, config);

    bench.close();
    timeouter.cancel();
}

/**
 * AZIONE DI REFACTORING (Extract Method):
 * La logica per il parsing degli argomenti della riga di comando è stata estratta
 * dal 'main' in questo metodo dedicato. Questo isola la gestione degli input
 * e rende il 'main' più focalizzato sull'orchestrazione.
 */
private static BenchmarkConfig parseArguments(String[] args) throws ParseException {
    Options options = new Options();
    options.addOption("time", true, "Running time (seconds), default 60");
    options.addOption("entrysize", true, "Entry size (bytes), default 1024");
    options.addOption("ensemble", true, "Ensemble size, default 3");
    options.addOption("quorum", true, "Quorum size, default 2");
    options.addOption("ackQuorum", true, "Ack quorum size, default is same as quorum");
    options.addOption("throttle", true, "Max outstanding requests, default 10000");
    options.addOption("ledgers", true, "Number of ledgers, default 1");
    options.addOption("zookeeper", true, "Zookeeper ensemble, default \"localhost:2181\"");
    options.addOption("password", true, "Password used to create ledgers (default 'benchPasswd')");
    options.addOption("coordnode", true, "Coordination znode for multi client benchmarks (optional)");
    options.addOption("timeout", true, "Number of seconds after which to give up");
    options.addOption("sockettimeout", true, "Socket timeout for bookkeeper client. In seconds. Default 5");
    options.addOption("skipwarmup", false, "Skip warm up, default false");
    options.addOption("sendlimit", true, "Max number of entries to send. Default 20000000");
    options.addOption("latencyFile", true, "File to dump latencies. Default is latencyDump.dat");
    options.addOption("help", false, "This message");

    CommandLineParser parser = new PosixParser();
    CommandLine cmd = parser.parse(options, args);

    if (cmd.hasOption("help")) {
        HelpFormatter formatter = new HelpFormatter();
        formatter.printHelp("BenchThroughputLatency <options>", options);
        return null;
    }
    return new BenchmarkConfig(cmd);
}

private static void logParameters(BenchmarkConfig config) {
    LOG.warn("(Parameters received) running time: {}, entry size: {}, ensemble size: {}, quorum size: {}, "
                    + "throttle: {}, number of ledgers: {}, zk servers: {}, latency file: {}",
            config.runningTime, config.entrysize, config.ensemble, config.quorum,
            config.throttle, config.ledgers, config.servers, config.latencyFile);
}

private static Timer setupTimeout(BenchmarkConfig config) {
    Timer timeouter = new Timer();
    if (config.timeout > 0) {
        timeouter.schedule(new TimerTask() {
            public void run() {
                System.err.println("Timing out benchmark after " + config.timeout + "ms");
                System.exit(-1);
            }
        }, config.timeout);
    }
    return timeouter;
}

private static void runWarmup(BenchmarkConfig config) throws KeeperException, IOException, InterruptedException, BKException {
    LOG.info("Starting warmup");
    long throughput = warmUp(config.data, config.ledgers, config.ensemble, config.quorum, config.passwd, config.clientConf);
    LOG.info("Warmup tp: " + throughput);
    LOG.info("Warmup phase finished");
}

/**
 * AZIONE DI REFACTORING (Extract Method):
 * La logica complessa per la sincronizzazione tra più client tramite ZooKeeper
 * è stata isolata in questo metodo. Questo migliora la leggibilità del 'main',
 * che non deve più preoccuparsi dei dettagli di implementazione di ZooKeeper.
 */
private static ZooKeeper setupZooKeeperCoordination(BenchmarkConfig config) throws IOException, InterruptedException, KeeperException {
    if (config.coordinationZnode == null) {
        return null;
    }
    final CountDownLatch connectLatch = new CountDownLatch(1);
    ZooKeeper zk = new ZooKeeper(config.servers, 15000, event -> {
        if (event.getState() == Watcher.Event.KeeperState.SyncConnected) {
            connectLatch.countDown();
        }
    });
    if (!connectLatch.await(10, TimeUnit.SECONDS)) {
        LOG.error("Couldn't connect to zookeeper at " + config.servers);
        zk.close();
        System.exit(-1);
    }
    final CountDownLatch latch = new CountDownLatch(1);
    LOG.info("Waiting for " + config.coordinationZnode);
    if (zk.exists(config.coordinationZnode, event -> {
        if (event.getType() == Watcher.Event.EventType.NodeCreated) {
            latch.countDown();
        }
    }) != null) {
        latch.countDown();
    }
    latch.await();
    LOG.info("Coordination znode created");
    return zk;
}

/**
 * AZIONE DI REFACTORING (Extract Method):
 * Il calcolo delle statistiche (percentili, throughput) e il salvataggio dei
 * risultati su file sono stati estratti in un metodo dedicato. Questo separa
 * la fase di esecuzione del benchmark dalla fase di analisi dei risultati.
 */
private static void processAndSaveResults(BenchThroughputLatency bench, ZooKeeper zk, BenchmarkConfig config) throws IOException, KeeperException, InterruptedException {
    LOG.info("Calculating percentiles");
    long[] latency = Arrays.stream(bench.latencies).filter(l -> l > 0).toArray();
    Arrays.sort(latency);
    long numCompletions = latency.length;
    long tp = (long) ((double) (numCompletions * 1000.0) / (double) bench.getDuration());
    LOG.info("{} completions in {} seconds: {} ops/sec", numCompletions, bench.getDuration(), tp);

    if (zk != null) {
        zk.create(config.coordinationZnode + "/worker-",
                ("tp " + tp + " duration " + bench.getDuration()).getBytes(),
                ZooDefs.Ids.OPEN_ACL_UNSAFE, CreateMode.PERSISTENT_SEQUENTIAL);
        zk.close();
    }

    try (OutputStream fos = new BufferedOutputStream(new FileOutputStream(config.latencyFile))) {
        for (Long l : latency) {
            fos.write((l + "\t" + (l / 1000000) + "ms\n").getBytes());
        }
    }
    LOG.info("99th percentile latency: {}", percentile(latency, 99));
    LOG.info("95th percentile latency: {}", percentile(latency, 95));
}


