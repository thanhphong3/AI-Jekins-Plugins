package io.jenkins.plugins.ailoganalyzer;

import hudson.Extension;
import hudson.model.Action;
import hudson.model.Job;
import jenkins.model.TransientActionFactory;

import java.util.Collection;
import java.util.Collections;

@Extension
public class AiLogAnalyzerProjectActionFactory extends TransientActionFactory<Job> {

    @Override
    public Class<Job> type() {
        return Job.class;
    }

    @Override
    public Collection<? extends Action> createFor(Job target) {
        return Collections.singleton(new AiLogAnalyzerProjectAction(target));
    }
}
