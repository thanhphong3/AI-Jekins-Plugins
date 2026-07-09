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
        if (descriptor.getAutoTriggerJobs().contains(jobName)) {
            Result result = run.getResult();
            if (result != null && (result == Result.FAILURE || result == Result.UNSTABLE)) {
                listener.getLogger().println("=====================================================");
                listener.getLogger().println("[AI Log Analyzer] Job '" + jobName + "' is in global auto-trigger list. Starting AI analysis...");
                
                AiLogAnalyzerAction action = null;
                for (hudson.model.Action a : run.getActions()) {
                    if (a instanceof AiLogAnalyzerAction) {
                        action = (AiLogAnalyzerAction) a;
                        break;
                    }
                }
                
                if (action == null) {
                    // Create action with defaults
                    action = new AiLogAnalyzerAction(run, 500, null, descriptor.getDefaultAiModel());
                    run.addAction(action);
                } else if (action.getAnalysisResult() != null && !action.getAnalysisResult().isEmpty()) {
                    listener.getLogger().println("[AI Log Analyzer] Analysis already performed. Skipping.");
                    listener.getLogger().println("=====================================================");
                    return;
                }

                try {
                    String analysis = action.executeAnalysis(listener.getLogger());
                    action.setAnalysisResult(analysis);
                    run.save();

                    listener.getLogger().println("[AI Log Analyzer] AI Response:");
                    listener.getLogger().println(analysis);
                    listener.getLogger().println("=====================================================");
                } catch (Exception e) {
                    listener.getLogger().println("[AI Log Analyzer] Exception occurred during analysis: " + e.getMessage());
                    e.printStackTrace(listener.getLogger());
                    listener.getLogger().println("=====================================================");
                }
            }
        }
    }
}
