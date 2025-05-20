package controller;

import model.Release;
import model.Ticket;
import org.json.JSONArray;
import org.json.JSONObject;
import utils.JIRAUtils;

import java.io.IOException;
import java.net.URISyntaxException;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

public class ExtractFromJIRA {
    private final String projectName;

    public ExtractFromJIRA(String projectName) {
        this.projectName = projectName.toUpperCase();
    }

    public List<Release> getReleaseList() throws IOException {
        List<Release> releaseList = new ArrayList<>();

        String url = "https://issues.apache.org/jira/rest/api/latest/project/" + this.projectName;
        JSONObject json = JIRAUtils.readJsonFromUrl(url);
        JSONArray versions = json.getJSONArray("versions");

        for (int i = 0; i < versions.length(); i++) {

            JSONObject releaseJsonObject = versions.getJSONObject(i);

            if (versions.getJSONObject(i).has("releaseDate") && versions.getJSONObject(i).has("name")) {
                String releaseDate = releaseJsonObject.get("releaseDate").toString();
                String releaseName = releaseJsonObject.get("name").toString();
                releaseList.add(new Release(releaseName, LocalDate.parse(releaseDate)));
            }
        }

        releaseList.sort(Comparator.comparing(Release::getDate));
        int j = 0;
        for (Release release : releaseList) {
            release.setId(++j);
        }
        return releaseList;
    }

    //Retrieving all tickets of type BUG with status CLOSED or RESOLVED and resolution equals to FIXED (not Unresolved or others)
    public List<Ticket> getTicketList(List<Release> releasesList, boolean fix) throws IOException, URISyntaxException {
        int j;
        int i = 0;
        int total;
        List<Ticket> ticketsList = new ArrayList<>();
        do {
            j = i + 1000;
            String url = "https://issues.apache.org/jira/rest/api/2/search?jql=project=%22"
                    + this.projectName + "%22AND%22issueType%22=%22Bug%22AND" +
                    "(%22status%22=%22Closed%22OR%22status%22=%22Resolved%22)" +
                    "AND%22resolution%22=%22Fixed%22&fields=key,versions,created,resolutiondate&startAt="
                    + i + "&maxResults=" + j;
            JSONObject json = JIRAUtils.readJsonFromUrl(url);
            JSONArray issues = json.getJSONArray("issues");
            total = json.getInt("total");
            for (; i < total && i < j; i++) {
                //Iterate through each bug to retrieve ID, creation date, resolution date and affected versions
                String key = issues.getJSONObject(i % 1000).get("key").toString();
                JSONObject fields = issues.getJSONObject(i % 1000).getJSONObject("fields");
                String creationDateString = fields.get("created").toString();
                String resolutionDateString = fields.get("resolutiondate").toString();
                LocalDate creationDate = LocalDate.parse(creationDateString.substring(0, 10));
                LocalDate resolutionDate = LocalDate.parse(resolutionDateString.substring(0, 10));
                JSONArray affectedVersionsArray = fields.getJSONArray("versions");

                Release openingVersion = JIRAUtils.getReleaseAfterOrEqualDate(creationDate, releasesList);
                Release fixedVersion = JIRAUtils.getReleaseAfterOrEqualDate(resolutionDate, releasesList);

                List<Release> affectedVersionsList = JIRAUtils.returnAffectedVersions(affectedVersionsArray, releasesList);

                //The opening version must be after the injected version (first affected versions) and before the fixed version
                if (!affectedVersionsList.isEmpty() && openingVersion != null && fixedVersion != null && (!affectedVersionsList.get(0).getDate().isBefore(openingVersion.getDate()) || openingVersion.getDate().isAfter(fixedVersion.getDate()))) {
                    continue;
                }

                //The opening version must be different from the first release
                if (openingVersion != null && fixedVersion != null && openingVersion.getId() != releasesList.get(0).getId()) {
                    ticketsList.add(new Ticket(key, creationDate, resolutionDate, affectedVersionsList.isEmpty() ? null : affectedVersionsList.get(0), openingVersion, fixedVersion, affectedVersionsList));
                }

            }
        } while (i < total);
        ticketsList.sort(Comparator.comparing(Ticket::getResolutionDate));

        if(fix) {
            List<Ticket> fixedTicketsList;
            // add the AV (if missing) using proportion
            fixedTicketsList = JIRAUtils.addIVandAV(ticketsList, releasesList);
            fixedTicketsList.sort(Comparator.comparing(Ticket::getResolutionDate));
            return fixedTicketsList;
        } else {
            return ticketsList;
        }

    }

}
