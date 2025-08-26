package utils;

import controller.Proportion;
import model.Release;
import model.Ticket;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.*;
import java.net.URISyntaxException;
import java.net.URL;
import java.nio.charset.Charset;
import java.time.LocalDate;
import java.util.*;

public class JIRAUtils {

    public static JSONObject readJsonFromUrl(String url) throws IOException, JSONException {
        InputStream is = new URL(url).openStream();
        try {
            BufferedReader rd = new BufferedReader(new InputStreamReader(is, Charset.forName("UTF-8")));
            String jsonText = readAll(rd);
            return new JSONObject(jsonText);
        } finally {
            is.close();
        }
    }

    private static String readAll(Reader rd) throws IOException {
        StringBuilder sb = new StringBuilder();
        int cp;
        while ((cp = rd.read()) != -1) {
            sb.append((char) cp);
        }
        return sb.toString();
    }

    public static Release getReleaseAfterOrEqualDate(LocalDate specificDate, List<Release> releasesList) {

        //sorting the releases by their date
        releasesList.sort(Comparator.comparing(Release::getDate));

        //the first release which has a date after or equal to the one given is returned
        for (Release release : releasesList) {
            if (!release.getDate().isBefore(specificDate)) {
                return release;
            }
        }
        return null;
    }

    public static List<Release> returnAffectedVersions(JSONArray affectedVersionsArray, List<Release> releasesList) {
        List<Release> existingAffectedVersions = new ArrayList<>();

        for (int i = 0; i < affectedVersionsArray.length(); i++) {
            String affectedVersionName = affectedVersionsArray.getJSONObject(i).get("name").toString();

            //iterating through the releases to find the corresponding one
            for (Release release : releasesList) {
                if (Objects.equals(affectedVersionName, release.getName())) {
                    existingAffectedVersions.add(release);
                    break;
                }
            }
        }
        existingAffectedVersions.sort(Comparator.comparing(Release::getDate));
        return existingAffectedVersions;
    }

    public static List<Ticket> addIVandAV(List<Ticket> ticketsList, List<Release> releasesList, String projectName) throws IOException, URISyntaxException {
        List<Ticket> finalTicketsList = new ArrayList<>();

        Proportion proportion = new Proportion(projectName);

        for(Ticket ticket: ticketsList){
            if(ticket.getAv().isEmpty()){
                proportion.fixTicketWithProportion(ticket, releasesList);
                completeAV(ticket, releasesList);
            }else{
                proportion.addProportion(ticket);
                completeAV(ticket, releasesList);
            }
            finalTicketsList.add(ticket);
        }

        return finalTicketsList;
    }

    public static void completeAV(Ticket ticket, List<Release> releases){
        int iv = ticket.getIv().getId();
        int fv = ticket.getFv().getId();

        for(Release release : releases){
            if(release.getId() > iv && release.getId() < fv ){
                ticket.addAv(release);
            }
        }
    }

    public static List<Ticket> returnValidTickets(List<Ticket> ticketList){
        List<Ticket> correctTicket = new ArrayList<>();

        for(Ticket ticket: ticketList){
            if(!ticket.getAv().isEmpty())  correctTicket.add(ticket);
        }

        return correctTicket;
    }

    public static float median(List<Float> array){
        float median;

        Collections.sort(array);

        int size = array.size();
        if (size % 2 == 0) {
            median = (array.get((size / 2) - 1) + array.get(size / 2)) / 2;
        } else {
            median = array.get(size / 2);
        }

        return median;
    }


}


