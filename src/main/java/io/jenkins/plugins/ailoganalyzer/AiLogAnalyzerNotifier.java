package io.jenkins.plugins.ailoganalyzer;

import hudson.Extension;
import hudson.FilePath;
import hudson.Launcher;
import hudson.model.AbstractProject;
import hudson.model.Result;
import hudson.model.Run;
import hudson.model.TaskListener;
import hudson.tasks.BuildStepDescriptor;
import hudson.tasks.BuildStepMonitor;
import hudson.tasks.Notifier;
import hudson.tasks.Publisher;
import hudson.util.FormValidation;
import hudson.util.ListBoxModel;
import hudson.util.Secret;
import jenkins.tasks.SimpleBuildStep;
import net.sf.json.JSONObject;
import org.jenkinsci.Symbol;
import org.kohsuke.stapler.DataBoundConstructor;
import org.kohsuke.stapler.QueryParameter;
import org.kohsuke.stapler.StaplerRequest;
import org.kohsuke.stapler.bind.JavaScriptMethod;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.List;

public class AiLogAnalyzerNotifier extends Notifier implements SimpleBuildStep {

    private final int maxLogLines;
    private final String customPromptPrefix;
    private final boolean runAutomatically;
    private final String aiModel;

    @DataBoundConstructor
    public AiLogAnalyzerNotifier(int maxLogLines, String customPromptPrefix, boolean runAutomatically, String aiModel) {
        this.maxLogLines = maxLogLines > 0 ? maxLogLines : 500;
        this.customPromptPrefix = (customPromptPrefix != null && !customPromptPrefix.trim().isEmpty()) 
                ? customPromptPrefix 
                : "Please analyze this build log and find the root cause of the error:";
        this.runAutomatically = runAutomatically;
        this.aiModel = (aiModel != null && !aiModel.trim().isEmpty()) ? aiModel : "autodetect";
    }

    public int getMaxLogLines() {
        return maxLogLines;
    }

    public String getCustomPromptPrefix() {
        return customPromptPrefix;
    }

    public boolean isRunAutomatically() {
        return runAutomatically;
    }

    public String getAiModel() {
        return aiModel;
    }

    @Override
    public BuildStepMonitor getRequiredMonitorService() {
        return BuildStepMonitor.NONE;
    }

    @Override
    public void perform(Run<?, ?> run, FilePath workspace, Launcher launcher, TaskListener listener) throws InterruptedException, IOException {
        // Always attach the AiLogAnalyzerAction to the build so the sidebar menu is available.
        AiLogAnalyzerAction action = run.getAction(AiLogAnalyzerAction.class);
        if (action == null) {
            action = new AiLogAnalyzerAction(run, maxLogLines, customPromptPrefix, aiModel);
            run.addAction(action);
        }

        // If configured to run automatically, perform the analysis on failure or unstable
        if (runAutomatically) {
            Result result = run.getResult();
            if (result != null && (result == Result.FAILURE || result == Result.UNSTABLE)) {
                listener.getLogger().println("=====================================================");
                listener.getLogger().println("[AI Log Analyzer] Analyzing build failure automatically...");

                try {
                    String analysis = action.executeAnalysis(listener.getLogger());
                    action.setAnalysisResult(analysis);
                    run.save();

                    listener.getLogger().println("[AI Log Analyzer] AI Response:");
                    listener.getLogger().println(analysis);
                    listener.getLogger().println("=====================================================");
                } catch (Exception e) {
                    listener.getLogger().println("[AI Log Analyzer] Exception occurred: " + e.getMessage());
                    e.printStackTrace(listener.getLogger());
                }
            } else {
                listener.getLogger().println("[AI Log Analyzer] Build succeeded or status unavailable. Skipping automatic analysis.");
            }
        } else {
            listener.getLogger().println("[AI Log Analyzer] Manual analysis configured. The 'AI Log Analysis' button is available in the sidebar.");
        }
    }

    @Override
    public DescriptorImpl getDescriptor() {
        return (DescriptorImpl) super.getDescriptor();
    }

    @Symbol("aiLogAnalyzer")
    @Extension
    public static final class DescriptorImpl extends BuildStepDescriptor<Publisher> {

        private String apiEndpointUrl;
        private Secret apiKey;

        public DescriptorImpl() {
            load();
        }

        public String getApiEndpointUrl() {
            return apiEndpointUrl;
        }

        public Secret getApiKey() {
            return apiKey;
        }

        @Override
        public boolean isApplicable(Class<? extends AbstractProject> aClass) {
            return true;
        }

        @Override
        public String getDisplayName() {
            return "AI Log Analyzer (Gameloft AI)";
        }

        @Override
        public boolean configure(StaplerRequest req, JSONObject json) throws FormException {
            this.apiEndpointUrl = json.getString("apiEndpointUrl");
            this.apiKey = Secret.fromString(json.getString("apiKey"));
            save();
            return super.configure(req, json);
        }

        public FormValidation doCheckApiEndpointUrl(@QueryParameter String value) {
            if (value.isEmpty()) {
                return FormValidation.warning("Please provide the AI API Endpoint URL.");
            }
            return FormValidation.ok();
        }
        
        public FormValidation doCheckMaxLogLines(@QueryParameter String value) {
            try {
                int lines = Integer.parseInt(value);
                if (lines <= 0) {
                    return FormValidation.error("Must be a positive integer.");
                }
                return FormValidation.ok();
            } catch (NumberFormatException e) {
                return FormValidation.error("Must be a valid integer.");
            }
        }

        public ListBoxModel doFillAiModelItems(@QueryParameter String aiModel) {
            ListBoxModel items = new ListBoxModel();
            items.add("autodetect (Smart Fallback)", "autodetect");
            if (aiModel != null && !aiModel.isEmpty() && !aiModel.equals("autodetect")) {
                items.add(aiModel, aiModel);
            }
            return items;
        }

        @JavaScriptMethod
        public List<String> getAvailableModels() {
            String endpointUrl = getApiEndpointUrl();
            Secret apiKey = getApiKey();
            if (endpointUrl == null || endpointUrl.isEmpty()) {
                return java.util.Collections.emptyList();
            }
            return AiLogAnalyzerAction.autodetectModelsStatic(endpointUrl, apiKey, null);
        }
    }
}
