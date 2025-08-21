/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 *
 */
package org.apache.bookkeeper.benchmark;

import java.io.BufferedOutputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Random;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.Timer;
import java.util.TimerTask;

import org.apache.bookkeeper.client.BKException;
import org.apache.bookkeeper.client.BookKeeper;
import org.apache.bookkeeper.client.LedgerHandle;
import org.apache.bookkeeper.client.AsyncCallback.AddCallback;
import org.apache.bookkeeper.conf.ClientConfiguration;
import org.apache.commons.cli.CommandLine;
import org.apache.commons.cli.CommandLineParser;
import org.apache.commons.cli.HelpFormatter;
import org.apache.commons.cli.Options;
import org.apache.commons.cli.ParseException;
import org.apache.commons.cli.PosixParser;
import org.apache.zookeeper.CreateMode;
import org.apache.zookeeper.KeeperException;
import org.apache.zookeeper.WatchedEvent;
import org.apache.zookeeper.Watcher;
import org.apache.zookeeper.ZooDefs;
import org.apache.zookeeper.ZooKeeper;
import org.apache.zookeeper.Watcher.Event.EventType;
import org.apache.zookeeper.Watcher.Event.KeeperState;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class BenchThroughputLatency implements AddCallback, Runnable {
    static Logger LOG = LoggerFactory.getLogger(BenchThroughputLatency.class);

    BookKeeper bk;
    LedgerHandle lh[];
    AtomicLong counter;

    Semaphore sem;
    int numberOfLedgers = 1;
    final int sendLimit;
    final long latencies[];

    static class Context {
        long localStartTime;
        long id;

        Context(long id, long time){
            this.id = id;
            this.localStartTime = time;
        }
    }

    public BenchThroughputLatency(int ensemble, int writeQuorumSize, int ackQuorumSize, byte[] passwd,
            int numberOfLedgers, int sendLimit, ClientConfiguration conf)
            throws KeeperException, IOException, InterruptedException {
        this.sem = new Semaphore(conf.getThrottleValue());
        bk = new BookKeeper(conf);
        this.counter = new AtomicLong(0);
        this.numberOfLedgers = numberOfLedgers;
        this.sendLimit = sendLimit;
        this.latencies = new long[sendLimit];
        try{
            lh = new LedgerHandle[this.numberOfLedgers];

            for(int i = 0; i < this.numberOfLedgers; i++) {
                lh[i] = bk.createLedger(ensemble, writeQuorumSize,
                                        ackQuorumSize,
                                        BookKeeper.DigestType.CRC32,
                                        passwd);
                LOG.debug("Ledger Handle: " + lh[i].getId());
            }
        } catch (BKException e) {
            e.printStackTrace();
        }
    }

    Random rand = new Random();
    public void close() throws InterruptedException, BKException {
        for(int i = 0; i < numberOfLedgers; i++) {
            lh[i].close();
        }
        bk.close();
    }

    long previous = 0;
    byte bytes[];

    void setEntryData(byte data[]) {
        bytes = data;
    }

    int lastLedger = 0;
    private int getRandomLedger() {
         return rand.nextInt(numberOfLedgers);
    }

    int latencyIndex = -1;
    AtomicLong completedRequests = new AtomicLong(0);

    long duration = -1;
    synchronized public long getDuration() {
        return duration;
    }

    public void run() {
        LOG.info("Running...");
        long start = previous = System.currentTimeMillis();

        int sent = 0;

        Thread reporter = new Thread() {
                public void run() {
                    try {
                        while(true) {
                            Thread.sleep(1000);
                            LOG.info("ms: {} req: {}", System.currentTimeMillis(), completedRequests.getAndSet(0));
                        }
                    } catch (InterruptedException ie) {
                        LOG.info("Caught interrupted exception, going away");
                    }
                }
            };
        reporter.start();
        long beforeSend = System.nanoTime();

        while(!Thread.currentThread().isInterrupted() && sent < sendLimit) {
            try {
                sem.acquire();
                if (sent == 10000) {
                    long afterSend = System.nanoTime();
                    long time = afterSend - beforeSend;
                    LOG.info("Time to send first batch: {}s {}ns ",
                             time/1000/1000/1000, time);
                }
            } catch (InterruptedException e) {
                break;
            }

            final int index = getRandomLedger();
            LedgerHandle h = lh[index];
            if (h == null) {
                LOG.error("Handle " + index + " is null!");
            } else {
                long nanoTime = System.nanoTime();
                lh[index].asyncAddEntry(bytes, this, new Context(sent, nanoTime));
                counter.incrementAndGet();
            }
            sent++;
        }
        LOG.info("Sent: "  + sent);
        try {
            int i = 0;
            while(this.counter.get() > 0) {
                Thread.sleep(1000);
                i++;
                if (i > 30) {
                    break;
                }
            }
        } catch(InterruptedException e) {
            LOG.error("Interrupted while waiting", e);
        }
        synchronized(this) {
            duration = System.currentTimeMillis() - start;
        }
        throughput = sent*1000/getDuration();

        reporter.interrupt();
        try {
            reporter.join();
        } catch (InterruptedException ie) {
            // ignore
        }
        LOG.info("Finished processing in ms: " + getDuration() + " tp = " + throughput);
    }

    long throughput = -1;
    public long getThroughput() {
        return throughput;
    }

    long threshold = 20000;
    long runningAverageCounter = 0;
    long totalTime = 0;
    @Override
    public void addComplete(int rc, LedgerHandle lh, long entryId, Object ctx) {
        Context context = (Context) ctx;

        // we need to use the id passed in the context in the case of
        // multiple ledgers, and it works even with one ledger
        entryId = context.id;
        long newTime = System.nanoTime() - context.localStartTime;

        sem.release();
        counter.decrementAndGet();

        if (rc == 0) {
            latencies[(int)entryId] = newTime;
            completedRequests.incrementAndGet();
        }
    }

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


    private static double percentile(long[] latency, int percentile) {
        int size = latency.length;
        int sampleSize = (size * percentile) / 100;
        long total = 0;
        int count = 0;
        for(int i = 0; i < sampleSize; i++) {
            total += latency[i];
            count++;
        }
        return ((double)total/(double)count)/1000000.0;
    }

    private static long warmUp(byte[] data, int ledgers, int ensemble, int qSize,
                               byte[] passwd, ClientConfiguration conf)
            throws KeeperException, IOException, InterruptedException, BKException {
        final CountDownLatch connectLatch = new CountDownLatch(1);
        final int bookies;
        String bookieRegistrationPath = conf.getZkAvailableBookiesPath();
        ZooKeeper zk = null;
        try {
            final String servers = conf.getZkServers();
            zk = new ZooKeeper(servers, 15000, new Watcher() {
                    @Override
                    public void process(WatchedEvent event) {
                        if (event.getState() == KeeperState.SyncConnected) {
                            connectLatch.countDown();
                        }
                    }});
            if (!connectLatch.await(10, TimeUnit.SECONDS)) {
                LOG.error("Couldn't connect to zookeeper at " + servers);
                throw new IOException("Couldn't connect to zookeeper " + servers);
            }
            bookies = zk.getChildren(bookieRegistrationPath, false).size();
        } finally {
            if (zk != null) {
                zk.close();
            }
        }

        BenchThroughputLatency warmup = new BenchThroughputLatency(bookies, bookies, bookies, passwd,
                                                                   ledgers, 10000, conf);
        warmup.setEntryData(data);
        Thread thread = new Thread(warmup);
        thread.start();
        thread.join();
        warmup.close();
        return warmup.getThroughput();
    }
}
