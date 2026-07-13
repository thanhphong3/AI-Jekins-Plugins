package io.jenkins.plugins.ailoganalyzer;

import hudson.Extension;
import hudson.ExtensionList;
import hudson.model.Result;
import hudson.model.Run;
import hudson.model.TaskListener;
import hudson.model.listeners.RunListener;
import java.io.IOException;

@Extension
public class AiLogAnalyzerRunListener extends RunListener<Run> {

    @Override
    public void onCompleted(Run run, TaskListener listener) {
        AiLogAnalyzerNotifier.DescriptorImpl descriptor = ExtensionList.lookupSingleton(AiLogAnalyzerNotifier.DescriptorImpl.class);
        if (descriptor == null) {
            return;
        }

        String jobName = run.getParent().getFullName();
        boolean trigger = descriptor.getAutoTriggerJobs().contains(jobName);

        AiLogAnalyzerAction action = null;
        for (hudson.model.Action a : run.getActions()) {
            if (a instanceof AiLogAnalyzerAction) {
                action = (AiLogAnalyzerAction) a;
                break;
            }
        }

        if (action != null && action.isAutoTrigger()) {
            trigger = true;
        }

        if (trigger) {
            Result result = run.getResult();
            if (result != null && (result == Result.FAILURE || result == Result.UNSTABLE)) {
                if (action == null) {
                    // Create action with defaults
                    action = new AiLogAnalyzerAction(run, 500, null, descriptor.getDefaultAiModel());
                    action.setAutoTrigger(true);
                    run.addAction(action);
                } else if (action.getAnalysisResult() != null && !action.getAnalysisResult().isEmpty()) {
                    return;
                }

                action.startBackgroundAnalysis(action.getAiModel(), "system");
            }
        }
    }
}
