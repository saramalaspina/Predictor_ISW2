package utils;

import model.Release;
import org.eclipse.jgit.revwalk.RevCommit;

import java.text.SimpleDateFormat;
import java.time.LocalDate;
import java.util.Date;
import java.util.List;

public class GitUtils {

    public static Release getReleaseOfCommit(RevCommit commit, List<Release> releaseList) {

        SimpleDateFormat formatter = new SimpleDateFormat("yyyy-MM-dd");
        LocalDate commitDate = LocalDate.parse(formatter.format(commit.getCommitterIdent().getWhen()));
        LocalDate lowerBoundDate = LocalDate.parse(formatter.format(new Date(0)));
        for (Release release : releaseList) {
            LocalDate dateOfRelease = release.getDate();
            if (commitDate.isAfter(lowerBoundDate) && !commitDate.isAfter(dateOfRelease)) {
                return release;
            }
            lowerBoundDate = dateOfRelease;
        }
        return null;
    }

}
