package controller;

import model.Release;
import model.Ticket;
import utils.JIRAUtils;

import java.io.IOException;
import java.net.URISyntaxException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static java.lang.Math.max;

public class Proportion {

    private List<Float> proportionList;

    private float totalProportion;

    private final int thresholdColdStart;

    private static final Map<Projects, Float> cachedColdStartProportions = new HashMap<>();


    private enum Projects {
        AVRO,
        SYNCOPE,
        STORM,
        ZOOKEEPER
    }

    public Proportion() {
        this.proportionList = new ArrayList<>();
        this.totalProportion = 0;
        this.thresholdColdStart = 5;
    }

    public void fixTicketWithProportion(Ticket ticket, List<Release> releaseList) throws IOException, URISyntaxException {
        int estimatedIV;
        float proportion;

        if (proportionList.size() < this.thresholdColdStart) {
            proportion = coldStart();
        } else {
            proportion = increment();
        }

        estimatedIV = obtainIV(proportion, ticket);

        for (Release release : releaseList) {
            if (estimatedIV == release.getId()) {
                ticket.setIv(release);
                ticket.addAv(release);
            }
        }

        if (ticket.getIv() != null && ticket.getOv() != null && ticket.getFv() != null) {
            addProportion(ticket);
        }

    }

    private float increment() {
        return this.totalProportion / this.proportionList.size();
    }

    private float coldStart() throws IOException, URISyntaxException {
        List<Float> proportionListTemp = new ArrayList<>();

        for (Projects project : Projects.values()) {

            if (cachedColdStartProportions.containsKey(project)) {
                proportionListTemp.add(cachedColdStartProportions.get(project));
                continue;
            }

            ExtractFromJIRA jiraExtractor = new ExtractFromJIRA(project.toString().toUpperCase());
            List<Release> releaseList = jiraExtractor.getReleaseList();
            List<Ticket> ticketList = jiraExtractor.getTicketList(releaseList, false);
            //need to obtain all tickets that have AV set
            List<Ticket> consistentTickets = JIRAUtils.returnValidTickets(ticketList);
            if (consistentTickets.size() >= this.thresholdColdStart) {
                Proportion proportion = new Proportion();

                for (Ticket t : consistentTickets) {
                    proportion.addProportion(t);
                }

                float avgProportion = proportion.increment();
                proportionListTemp.add(avgProportion);
                cachedColdStartProportions.put(project, avgProportion);
            }
        }

        return JIRAUtils.median(proportionListTemp);

    }

    private int obtainIV(float proportion, Ticket ticket) {
        int ov = ticket.getOv().getId();
        int fv = ticket.getFv().getId();
        int estimatedIV;

        if (ov != fv) {
            estimatedIV = max(1, (int) (fv - proportion * (fv - ov)));
        } else {
            estimatedIV = max(1, (int) (fv - proportion));
        }

        return estimatedIV;
    }

    public void addProportion(Ticket ticket) {
        int denominator;
        float proportion;
        int ov = ticket.getOv().getId();
        int fv = ticket.getFv().getId();

        if (ov == fv) {
            denominator = 1;
        } else {
            denominator = fv - ov;
        }

        proportion = (float) (fv - ticket.getIv().getId()) / denominator;

        this.proportionList.add(proportion);
        this.totalProportion += proportion;

    }
}