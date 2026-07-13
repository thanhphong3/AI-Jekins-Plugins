package io.jenkins.plugins.ailoganalyzer;

import hudson.Extension;
import hudson.model.Action;
import hudson.model.Run;
import jenkins.model.TransientActionFactory;

import java.util.Collection;
import java.util.Collections;

@Extension
public class AiLogAnalyzerRunActionFactory extends TransientActionFactory<Run> {

    @Override
    public Class<Run> type() {
        return Run.class;
    }

    @Override
    public Collection<? extends Action> createFor(Run target) {
        for (Action action : target.getActions()) {
            if (action instanceof AiLogAnalyzerAction) {
                return Collections.emptyList();
            }
        }

        return Collections.singleton(new AiLogAnalyzerAction(target, 500, null, "autodetect"));
    }
}
