//metodo originale
public static void main(String[] args) throws KeeperException, IOException, InterruptedException, ParseException, BKException {
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
        System.exit(-1);
    }

    long runningTime = Long.valueOf(cmd.getOptionValue("time", "60"));
    String servers = cmd.getOptionValue("zookeeper", "localhost:2181");
    int entrysize = Integer.valueOf(cmd.getOptionValue("entrysize", "1024"));

    int ledgers = Integer.valueOf(cmd.getOptionValue("ledgers", "1"));
    int ensemble = Integer.valueOf(cmd.getOptionValue("ensemble", "3"));
    int quorum = Integer.valueOf(cmd.getOptionValue("quorum", "2"));
    int ackQuorum = quorum;
    if (cmd.hasOption("ackQuorum")) {
        ackQuorum = Integer.valueOf(cmd.getOptionValue("ackQuorum"));
    }
    int throttle = Integer.valueOf(cmd.getOptionValue("throttle", "10000"));
    int sendLimit = Integer.valueOf(cmd.getOptionValue("sendlimit", "20000000"));

    final int sockTimeout = Integer.valueOf(cmd.getOptionValue("sockettimeout", "5"));

    String coordinationZnode = cmd.getOptionValue("coordnode");
    final byte[] passwd = cmd.getOptionValue("password", "benchPasswd").getBytes();

    String latencyFile = cmd.getOptionValue("latencyFile", "latencyDump.dat");

    Timer timeouter = new Timer();
    if (cmd.hasOption("timeout")) {
        final long timeout = Long.valueOf(cmd.getOptionValue("timeout", "360")) * 1000;

        timeouter.schedule(new TimerTask() {
            public void run() {
                System.err.println("Timing out benchmark after " + timeout + "ms");
                System.exit(-1);
            }
        }, timeout);
    }

    LOG.warn("(Parameters received) running time: " + runningTime +
            ", entry size: " + entrysize + ", ensemble size: " + ensemble +
            ", quorum size: " + quorum +
            ", throttle: " + throttle +
            ", number of ledgers: " + ledgers +
            ", zk servers: " + servers +
            ", latency file: " + latencyFile);

    long totalTime = runningTime * 1000;

    // Do a warmup run
    Thread thread;

    byte data[] = new byte[entrysize];
    Arrays.fill(data, (byte) 'x');

    ClientConfiguration conf = new ClientConfiguration();
    conf.setThrottleValue(throttle).setReadTimeout(sockTimeout).setZkServers(servers);

    if (!cmd.hasOption("skipwarmup")) {
        long throughput;
        LOG.info("Starting warmup");

        throughput = warmUp(data, ledgers, ensemble, quorum, passwd, conf);
        LOG.info("Warmup tp: " + throughput);
        LOG.info("Warmup phase finished");
    }


    // Now do the benchmark
    BenchThroughputLatency bench = new BenchThroughputLatency(ensemble, quorum, ackQuorum,
            passwd, ledgers, sendLimit, conf);
    bench.setEntryData(data);
    thread = new Thread(bench);
    ZooKeeper zk = null;

    if (coordinationZnode != null) {
        final CountDownLatch connectLatch = new CountDownLatch(1);
        zk = new ZooKeeper(servers, 15000, new Watcher() {
            @Override
            public void process(WatchedEvent event) {
                if (event.getState() == KeeperState.SyncConnected) {
                    connectLatch.countDown();
                }
            }
        });
        if (!connectLatch.await(10, TimeUnit.SECONDS)) {
            LOG.error("Couldn't connect to zookeeper at " + servers);
            zk.close();
            System.exit(-1);
        }

        final CountDownLatch latch = new CountDownLatch(1);
        LOG.info("Waiting for " + coordinationZnode);
        if (zk.exists(coordinationZnode, new Watcher() {
            @Override
            public void process(WatchedEvent event) {
                if (event.getType() == EventType.NodeCreated) {
                    latch.countDown();
                }
            }
        }) != null) {
            latch.countDown();
        }
        latch.await();
        LOG.info("Coordination znode created");
    }
    thread.start();
    Thread.sleep(totalTime);
    thread.interrupt();
    thread.join();

    LOG.info("Calculating percentiles");

    int numlat = 0;
    for (int i = 0; i < bench.latencies.length; i++) {
        if (bench.latencies[i] > 0) {
            numlat++;
        }
    }
    int numcompletions = numlat;
    numlat = Math.min(bench.sendLimit, numlat);
    long[] latency = new long[numlat];
    int j = 0;
    for (int i = 0; i < bench.latencies.length && j < numlat; i++) {
        if (bench.latencies[i] > 0) {
            latency[j++] = bench.latencies[i];
        }
    }
    Arrays.sort(latency);

    long tp = (long) ((double) (numcompletions * 1000.0) / (double) bench.getDuration());

    LOG.info(numcompletions + " completions in " + bench.getDuration() + " seconds: " + tp + " ops/sec");

    if (zk != null) {
        zk.create(coordinationZnode + "/worker-",
                ("tp " + tp + " duration " + bench.getDuration()).getBytes(),
                ZooDefs.Ids.OPEN_ACL_UNSAFE, CreateMode.PERSISTENT_SEQUENTIAL);
        zk.close();
    }

    // dump the latencies for later debugging (it will be sorted by entryid)
    OutputStream fos = new BufferedOutputStream(new FileOutputStream(latencyFile));

    for (Long l : latency) {
        fos.write((Long.toString(l) + "\t" + (l / 1000000) + "ms\n").getBytes());
    }
    fos.flush();
    fos.close();

    // now get the latencies
    LOG.info("99th percentile latency: {}", percentile(latency, 99));
    LOG.info("95th percentile latency: {}", percentile(latency, 95));

    bench.close();
    timeouter.cancel();
}
