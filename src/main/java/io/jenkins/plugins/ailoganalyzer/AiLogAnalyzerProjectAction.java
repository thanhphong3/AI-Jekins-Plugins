package io.jenkins.plugins.ailoganalyzer;

import hudson.model.Action;
import hudson.model.Job;
import hudson.model.Run;
import org.kohsuke.stapler.StaplerRequest;
import org.kohsuke.stapler.StaplerResponse;

import java.io.IOException;
import javax.servlet.ServletException;

public class AiLogAnalyzerProjectAction implements Action {

    private final Job<?, ?> job;

    public AiLogAnalyzerProjectAction(Job<?, ?> job) {
        this.job = job;
    }

    @Override
    public String getIconFileName() {
        return "symbol-search";
    }

    @Override
    public String getDisplayName() {
        return "AI Log Analysis";
    }

    @Override
    public String getUrlName() {
        return "ai-log-analysis";
    }

    public Job<?, ?> getJob() {
        return job;
    }

    public void doIndex(StaplerRequest req, StaplerResponse rsp) throws IOException, ServletException {
        Run<?, ?> lastBuild = job.getLastBuild();
        if (lastBuild != null) {
            rsp.sendRedirect2(req.getContextPath() + "/" + lastBuild.getUrl() + "ai-log-analysis");
        } else {
            rsp.sendRedirect2(req.getContextPath() + "/" + job.getUrl());
        }
    }
}
