package model;

import org.eclipse.jgit.revwalk.RevCommit;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

public class Ticket {

    private String id;
    private LocalDate creationDate;
    private LocalDate resolutionDate;
    private Release iv;
    private Release ov;
    private Release fv;
    private List<Release> av;
    private List<RevCommit> commitList;

    public Ticket(String id, LocalDate creationDate, LocalDate resolutionDate, Release iv, Release ov, Release fv, List<Release> av) {
        this.id = id;
        this.creationDate = creationDate;
        this.resolutionDate = resolutionDate;
        this.iv = iv;
        this.ov = ov;
        this.fv = fv;
        this.av = av;
        this.commitList = new ArrayList<>();
    }

    public String getId() {
        return id;
    }

    public void setId(String id) {
        this.id = id;
    }

    public LocalDate getCreationDate() {
        return creationDate;
    }

    public void setCreationDate(LocalDate creationDate) {
        this.creationDate = creationDate;
    }

    public LocalDate getResolutionDate() {
        return resolutionDate;
    }

    public void setResolutionDate(LocalDate resolutionDate) {
        this.resolutionDate = resolutionDate;
    }

    public Release getIv() {
        return iv;
    }

    public void setIv(Release iv) {
        this.iv = iv;
    }

    public Release getOv() {
        return ov;
    }

    public void setOv(Release ov) {
        this.ov = ov;
    }

    public Release getFv() {
        return fv;
    }

    public void setFv(Release fv) {
        this.fv = fv;
    }

    public List<Release> getAv() {
        return av;
    }

    public void setAv(List<Release> av) {
        this.av = av;
    }

    public void addAv(Release av) {
        this.av.add(av);
    }

    public List<RevCommit> getCommitList() {
        return commitList;
    }

    public void addCommit(RevCommit commit){
        this.commitList.add(commit);
    }
}
