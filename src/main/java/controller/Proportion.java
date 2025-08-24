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

    private final List<Float> proportionList;

    private float totalProportion;

    private final int thresholdColdStart;

    private final String projectName;

    private static final Map<String, Float> cachedColdStartProportions = new HashMap<>();


    public Proportion(String projectName) {
        this.proportionList = new ArrayList<>();
        this.totalProportion = 0;
        this.thresholdColdStart = 5;
        this.projectName = projectName;
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
        if (this.proportionList.isEmpty()) {
            return 0.0f;
        }
        return this.totalProportion / this.proportionList.size();
    }

    private float coldStart() throws IOException, URISyntaxException {
        if (cachedColdStartProportions.containsKey(this.projectName)) {
            return cachedColdStartProportions.get(this.projectName);
        }

        ExtractFromJIRA jiraExtractor = new ExtractFromJIRA(this.projectName.toUpperCase());
        List<Release> releaseList = jiraExtractor.getReleaseList();
        List<Ticket> ticketList = jiraExtractor.getTicketList(releaseList, false);

        List<Ticket> consistentTickets = JIRAUtils.returnValidTickets(ticketList);

        if (consistentTickets.size() < this.thresholdColdStart) {
            throw new IllegalStateException(
                    "Impossibile calcolare la proporzione di cold-start per il progetto '" + this.projectName +
                            "'. Richiesti almeno " + this.thresholdColdStart + " ticket storici validi, ma trovati " +
                            consistentTickets.size() + "."
            );
        }


        Proportion tempProportionCalculator = new Proportion(this.projectName);
        for (Ticket t : consistentTickets) {
            tempProportionCalculator.addProportion(t);
        }
        float avgProportion = tempProportionCalculator.increment();

        cachedColdStartProportions.put(this.projectName, avgProportion);

        return avgProportion;
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