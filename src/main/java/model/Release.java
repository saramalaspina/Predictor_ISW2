package model;

import org.eclipse.jgit.revwalk.RevCommit;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

public class Release {

    private int id;
    private String name;
    private LocalDate date;
    private List<RevCommit> commitList;
    private List <JavaMethod> methodList;     //list of all methods related to that version

    public Release(String name, LocalDate date) {
        this.name = name;
        this.date = date;
        this.commitList = new ArrayList<>();
        this.methodList = new ArrayList<>();
    }

    public int getId() {
        return id;
    }

    public void setId(int id) {
        this.id = id;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public LocalDate getDate() {
        return date;
    }

    public void setDate(LocalDate date) {
        this.date = date;
    }

    public List<JavaMethod> getMethodList() {
        return methodList;
    }

    public void setMethodList(List<JavaMethod> methods) {
        this.methodList = methods;
    }

    public void addMethod(JavaMethod method){
        this.methodList.add(method);
    }

    public List<RevCommit> getCommitList() {
        return commitList;
    }

    public void addCommit(RevCommit commit){
        this.commitList.add(commit);
    }
}
