package io.jenkins.plugins.ailoganalyzer;

import hudson.ExtensionList;
import hudson.model.Run;
import hudson.util.Secret;
import hudson.model.User;
import jenkins.model.RunAction2;
import org.kohsuke.stapler.StaplerRequest;
import org.kohsuke.stapler.StaplerResponse;
import org.kohsuke.stapler.verb.POST;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.io.PrintStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import javax.servlet.ServletException;

public class AiLogAnalyzerAction implements RunAction2 {
    private transient Run<?, ?> run;
    private String analysisResult;
    private int maxLogLines;
    private String customPromptPrefix;
    private String aiModel;
    private transient volatile Thread activeThread;
    private transient volatile HttpURLConnection activeConnection;
    private transient volatile boolean isAnalyzing;
    private String initiatorId;
    private String translationVn;
    private transient String lastError;

    private transient boolean isTransient = true;

    public boolean isTransient() {
        return isTransient;
    }

    private boolean autoTrigger;

    public boolean isAutoTrigger() {
        return autoTrigger;
    }

    public void setAutoTrigger(boolean autoTrigger) {
        this.autoTrigger = autoTrigger;
    }

    public AiLogAnalyzerAction(Run<?, ?> run, int maxLogLines, String customPromptPrefix, String aiModel) {
        this.run = run;
        this.maxLogLines = maxLogLines;
        this.customPromptPrefix = customPromptPrefix;
        this.aiModel = aiModel;
        this.isTransient = true;
    }

    public AiLogAnalyzerAction(String analysisResult) {
        this.analysisResult = analysisResult;
        this.maxLogLines = 500;
        this.customPromptPrefix = "You are a DevOps and Build Engineer expert. Analyze the build log below to identify the root cause of the failure (especially focusing on Unity or Xcode/iOS build errors if applicable). Follow these formatting guidelines strictly:\n" +
                "1. Provide a concise, direct explanation. Do not write long, wordy paragraphs.\n" +
                "2. Structure your response using these exact sections:\n" +
                "   - ## 📊 Build Status Summary\n" +
                "     Create a Markdown table with fields: | Attribute | Details |. Include: Build Status, Primary Error, Failed Stage, and Impacted Component.\n" +
                "   - ## 🔍 Root Cause Analysis\n" +
                "     Explain the primary cause in 2-3 sentences. Use code syntax highlighting for error logs, classes, methods, or error codes.\n" +
                "   - ## 🛠️ Troubleshooting & Resolution Steps\n" +
                "     Create a Markdown table with headers: | Priority | Recommended Action | Details / Commands |. Provide specific shell commands or troubleshooting steps tailored for Unity/Xcode (e.g. C# script errors, Provisioning Profiles, Signing certificates, CocoaPods, etc.).\n" +
                "   - ## 📋 Relevant Log Snippet\n" +
                "     A code block showing the critical failure logs.";
        this.aiModel = "autodetect";
        this.isTransient = true;
    }

    public String getAnalysisResult() {
        return analysisResult;
    }

    public void setAnalysisResult(String analysisResult) {
        this.analysisResult = analysisResult;
    }

    public int getMaxLogLines() {
        return maxLogLines > 0 ? maxLogLines : 500;
    }

    public String getCustomPromptPrefix() {
        return (customPromptPrefix != null && !customPromptPrefix.trim().isEmpty()) 
                ? customPromptPrefix 
                : "You are a DevOps and Build Engineer expert. Analyze the build log below to identify the root cause of the failure (especially focusing on Unity or Xcode/iOS build errors if applicable). Follow these formatting guidelines strictly:\n" +
                "1. Provide a concise, direct explanation. Do not write long, wordy paragraphs.\n" +
                "2. Structure your response using these exact sections:\n" +
                "   - ## 📊 Build Status Summary\n" +
                "     Create a Markdown table with fields: | Attribute | Details |. Include: Build Status, Primary Error, Failed Stage, and Impacted Component.\n" +
                "   - ## 🔍 Root Cause Analysis\n" +
                "     Explain the primary cause in 2-3 sentences. Use code syntax highlighting for error logs, classes, methods, or error codes.\n" +
                "   - ## 🛠️ Troubleshooting & Resolution Steps\n" +
                "     Create a Markdown table with headers: | Priority | Recommended Action | Details / Commands |. Provide specific shell commands or troubleshooting steps tailored for Unity/Xcode (e.g. C# script errors, Provisioning Profiles, Signing certificates, CocoaPods, etc.).\n" +
                "   - ## 📋 Relevant Log Snippet\n" +
                "     A code block showing the critical failure logs.";
    }

    public String getAiModel() {
        String modelToUse = aiModel;
        if (modelToUse == null || modelToUse.trim().isEmpty() || modelToUse.equalsIgnoreCase("autodetect")) {
            modelToUse = getDescriptor().getDefaultAiModel();
        }
        return (modelToUse != null && !modelToUse.trim().isEmpty()) ? modelToUse : "autodetect";
    }

    public boolean isAnalyzing() {
        return isAnalyzing || (activeThread != null && activeThread.isAlive());
    }

    public String getInitiatorId() {
        return initiatorId;
    }

    public String getTranslationVn() {
        return translationVn;
    }

    public void setTranslationVn(String translationVn) {
        this.translationVn = translationVn;
    }

    public String getLastError() {
        return lastError;
    }

    public String getCurrentUserId() {
        hudson.model.User currentUser = hudson.model.User.current();
        return currentUser != null ? currentUser.getId() : "anonymous";
    }

    public AiLogAnalyzerNotifier.DescriptorImpl getDescriptor() {
        return hudson.ExtensionList.lookupSingleton(AiLogAnalyzerNotifier.DescriptorImpl.class);
    }

    @Override
    public String getIconFileName() {
        return "symbol-search"; // Using Jenkins core icon, or generic document
    }

    @Override
    public String getDisplayName() {
        return "AI Log Analysis";
    }

    @Override
    public String getUrlName() {
        return "ai-log-analysis";
    }

    @Override
    public void onAttached(Run<?, ?> r) {
        this.run = r;
        this.isTransient = false;
    }

    @Override
    public void onLoad(Run<?, ?> r) {
        this.run = r;
        this.isTransient = false;
    }

    public Run<?, ?> getRun() {
        return run;
    }

    public String executeAnalysis(PrintStream logger) throws IOException {
        AiLogAnalyzerNotifier.DescriptorImpl descriptor = ExtensionList.lookupSingleton(AiLogAnalyzerNotifier.DescriptorImpl.class);
        String endpointUrl = descriptor.getApiEndpointUrl();
        Secret apiKey = descriptor.getApiKey();

        if (endpointUrl == null || endpointUrl.isEmpty()) {
            throw new IOException("API Endpoint URL is not configured globally in Jenkins settings.");
        }

        int linesToExtract = getMaxLogLines();
        int windowSize = Math.max(2500, linesToExtract);
        if (logger != null) {
            logger.println("[AI Log Analyzer] Reading last " + windowSize + " lines of log for smart filtering...");
        }

        // Get the log lines from Jenkins run
        List<String> rawLogLines = run.getLog(windowSize);
        if (logger != null) {
            logger.println("[AI Log Analyzer] Filtering log lines to find high-signal build errors...");
        }

        // Filter log content using LogFilter
        String logContent = LogFilter.filterLog(rawLogLines, 3, 5, linesToExtract);

        if (logger != null) {
            int originalSize = rawLogLines.size();
            int filteredSize = logContent.split("\n").length;
            logger.println("[AI Log Analyzer] Smart filtering complete. Reduced log size from " + 
                originalSize + " lines to " + filteredSize + " lines.");
        }

        String modelsStr = getAiModel();
        List<String> modelsList = new ArrayList<>();
        
        if (modelsStr == null || modelsStr.trim().isEmpty() || modelsStr.trim().equalsIgnoreCase("autodetect")) {
            List<String> detected = autodetectModelsStatic(endpointUrl, apiKey, logger);
            if (!detected.isEmpty()) {
                modelsList.addAll(detected);
            } else {
                if (logger != null) {
                    logger.println("[AI Log Analyzer] Autodetection returned no models. Falling back to default list: gemini-1.5-pro, gemini-1.5-flash");
                }
                modelsList.add("gemini-1.5-pro");
                modelsList.add("gemini-1.5-flash");
            }
        } else {
            String chosenModel = modelsStr.trim();
            modelsList.add(chosenModel);
            
            // Try to load active models from server for fallback
            List<String> detected = autodetectModelsStatic(endpointUrl, apiKey, null);
            for (String m : detected) {
                if (!m.equalsIgnoreCase(chosenModel)) {
                    modelsList.add(m);
                }
            }
        }

        String[] models = modelsList.toArray(new String[0]);
        IOException lastException = null;

        for (int i = 0; i < models.length; i++) {
            if (Thread.currentThread().isInterrupted()) {
                throw new IOException("Analysis stopped by user.");
            }
            String currentModel = models[i];
            try {
                if (logger != null) {
                    logger.println("[AI Log Analyzer] Sending request to AI API with model: " + currentModel + " (" + (i + 1) + "/" + models.length + ")...");
                }

                // Build the JSON payload
                org.json.JSONObject payload = new org.json.JSONObject();
                payload.put("prompt", getCustomPromptPrefix());
                payload.put("log", logContent);
                payload.put("model", currentModel);

                // Add standard OpenAI chat completions format
                org.json.JSONArray messages = new org.json.JSONArray();
                org.json.JSONObject userMessage = new org.json.JSONObject();
                userMessage.put("role", "user");
                userMessage.put("content", getCustomPromptPrefix() + "\n\n" + logContent);
                messages.put(userMessage);
                payload.put("messages", messages);

                // Call the API
                URL url = new URL(endpointUrl);
                HttpURLConnection conn = (HttpURLConnection) url.openConnection();
                this.activeConnection = conn;
                try {
                    conn.setRequestMethod("POST");
                    conn.setRequestProperty("Content-Type", "application/json");
                    conn.setRequestProperty("Accept", "application/json");
                    if (apiKey != null && !apiKey.getPlainText().isEmpty()) {
                        conn.setRequestProperty("Authorization", "Bearer " + apiKey.getPlainText());
                        conn.setRequestProperty("x-api-key", apiKey.getPlainText());
                    }
                    conn.setConnectTimeout(30000); // 30 seconds
                    conn.setReadTimeout(30000);
                    conn.setDoOutput(true);

                    try (OutputStream os = conn.getOutputStream()) {
                        byte[] input = payload.toString().getBytes(StandardCharsets.UTF_8);
                        os.write(input, 0, input.length);
                    }

                    int responseCode = conn.getResponseCode();
                    if (responseCode >= 200 && responseCode < 300) {
                        StringBuilder responseBuilder = new StringBuilder();
                        try (BufferedReader br = new BufferedReader(new InputStreamReader(conn.getInputStream(), StandardCharsets.UTF_8))) {
                            String responseLine;
                            while ((responseLine = br.readLine()) != null) {
                                responseBuilder.append(responseLine.trim());
                            }
                        }
                        
                        String rawResponse = responseBuilder.toString();
                        String result;
                        
                        try {
                            org.json.JSONObject jsonResponse = new org.json.JSONObject(rawResponse);
                            if (jsonResponse.has("choices")) {
                                org.json.JSONArray choices = jsonResponse.getJSONArray("choices");
                                if (choices.length() > 0) {
                                    org.json.JSONObject firstChoice = choices.getJSONObject(0);
                                    if (firstChoice.has("message")) {
                                        org.json.JSONObject msgObj = firstChoice.getJSONObject("message");
                                        if (msgObj.has("content")) {
                                            result = msgObj.getString("content");
                                            if (logger != null) {
                                                logger.println("[AI Log Analyzer] Analysis completed successfully with model: " + currentModel);
                                            }
                                            return result;
                                        }
                                    }
                                }
                            }
                            
                            if (jsonResponse.has("analysis")) {
                                result = jsonResponse.getString("analysis");
                            } else if (jsonResponse.has("message")) {
                                result = jsonResponse.getString("message");
                            } else {
                                result = jsonResponse.toString(2);
                            }
                        } catch (org.json.JSONException e) {
                            result = rawResponse;
                        }

                        if (logger != null) {
                            logger.println("[AI Log Analyzer] Analysis completed successfully with model: " + currentModel);
                        }
                        return result;
                    } else {
                        throw new IOException("API Request failed with HTTP status code " + responseCode);
                    }
                } finally {
                    this.activeConnection = null;
                }
            } catch (IOException e) {
                if (Thread.currentThread().isInterrupted()) {
                    throw new IOException("Analysis stopped by user.", e);
                }
                lastException = e;
                if (logger != null) {
                    logger.println("[AI Log Analyzer] Model " + currentModel + " failed: " + e.getMessage());
                    if (i < models.length - 1) {
                        logger.println("[AI Log Analyzer] Automatically switching to next fallback model...");
                    }
                }
            }
        }

        throw new IOException("All configured AI models failed. Last error: " + (lastException != null ? lastException.getMessage() : "Unknown error"));
    }

    public synchronized boolean startBackgroundAnalysis(final String selectedModel, String initiator) {
        if (isAnalyzing()) {
            return false;
        }

        this.initiatorId = initiator != null ? initiator : "system";
        this.isAnalyzing = true;
        this.lastError = null;
        this.translationVn = null; // Reset translation when re-running analysis

        // Ensure the action is persisted so subsequent requests (like polling) route to this instance
        boolean isAlreadyPersisted = false;
        for (hudson.model.Action action : run.getActions()) {
            if (action == this) {
                isAlreadyPersisted = true;
                break;
            }
        }
        if (!isAlreadyPersisted) {
            run.addAction(this);
        }
        try {
            run.save();
        } catch (IOException e) {
            // ignore
        }

        Thread thread = new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    if (selectedModel != null && !selectedModel.trim().isEmpty()) {
                        AiLogAnalyzerAction.this.aiModel = selectedModel.trim();
                    }
                    String result = executeAnalysis(null);
                    AiLogAnalyzerAction.this.analysisResult = result;
                } catch (Exception e) {
                    if (Thread.currentThread().isInterrupted()) {
                        AiLogAnalyzerAction.this.lastError = "Analysis stopped by user.";
                    } else {
                        AiLogAnalyzerAction.this.lastError = e.getMessage();
                    }
                } finally {
                    synchronized (AiLogAnalyzerAction.this) {
                        AiLogAnalyzerAction.this.activeThread = null;
                        AiLogAnalyzerAction.this.activeConnection = null;
                        AiLogAnalyzerAction.this.isAnalyzing = false;
                        AiLogAnalyzerAction.this.initiatorId = null;
                    }
                    try {
                        run.save();
                    } catch (IOException ioException) {
                        // ignore
                    }
                }
            }
        }, "AI-Log-Analyzer-Thread-" + run.getExternalizableId());

        this.activeThread = thread;
        thread.start();
        return true;
    }

    @POST
    public void doStartAnalysis(StaplerRequest req, StaplerResponse rsp) throws IOException, ServletException {
        run.checkPermission(Run.UPDATE);
        rsp.setContentType("application/json");

        net.sf.json.JSONObject json = new net.sf.json.JSONObject();
        
        final String selectedModel = req.getParameter("model");
        hudson.model.User currentUser = hudson.model.User.current();
        final String currentUserId = currentUser != null ? currentUser.getId() : "anonymous";

        boolean started = startBackgroundAnalysis(selectedModel, currentUserId);
        if (started) {
            json.put("status", "success");
            json.put("message", "Analysis started in background.");
        } else {
            json.put("status", "error");
            json.put("message", "Analysis is already running.");
        }
        rsp.getWriter().write(json.toString());
    }

    @POST
    public void doStopAnalysis(StaplerRequest req, StaplerResponse rsp) throws IOException, ServletException {
        run.checkPermission(Run.UPDATE);
        rsp.setContentType("application/json");

        net.sf.json.JSONObject json = new net.sf.json.JSONObject();
        boolean stopped = false;
        synchronized (this) {
            if (this.activeThread != null) {
                this.activeThread.interrupt();
                stopped = true;
            }
            if (this.activeConnection != null) {
                try {
                    this.activeConnection.disconnect();
                    stopped = true;
                } catch (Exception e) {
                    // Ignore
                }
            }
            this.activeThread = null;
            this.activeConnection = null;
            this.isAnalyzing = false;
            this.initiatorId = null;
        }

        if (stopped) {
            json.put("status", "success");
            json.put("message", "Analysis stopped successfully.");
        } else {
            json.put("status", "success");
            json.put("message", "No active analysis to stop.");
        }
        rsp.getWriter().write(json.toString());
    }

    public void doGetStatus(StaplerRequest req, StaplerResponse rsp) throws IOException, ServletException {
        run.checkPermission(hudson.model.Item.READ);
        rsp.setContentType("application/json");

        net.sf.json.JSONObject json = new net.sf.json.JSONObject();
        json.put("isAnalyzing", isAnalyzing());
        json.put("initiatorId", initiatorId != null ? initiatorId : "");
        json.put("currentUserId", getCurrentUserId());
        json.put("analysisResult", analysisResult != null ? analysisResult : "");
        json.put("lastError", lastError != null ? lastError : "");
        rsp.getWriter().write(json.toString());
    }

    @POST
    public void doTranslate(StaplerRequest req, StaplerResponse rsp) throws IOException, ServletException {
        run.checkPermission(Run.UPDATE);
        rsp.setContentType("application/json");

        net.sf.json.JSONObject json = new net.sf.json.JSONObject();
        try {
            String targetLang = req.getParameter("lang");
            if (targetLang == null) {
                targetLang = "vn";
            }

            if (targetLang.equalsIgnoreCase("en")) {
                json.put("status", "success");
                json.put("result", this.analysisResult);
            } else {
                if (this.translationVn == null || this.translationVn.trim().isEmpty()) {
                    if (this.analysisResult == null || this.analysisResult.trim().isEmpty()) {
                        throw new IOException("No analysis result to translate.");
                    }
                    String translated = executeTranslation(this.analysisResult, "vn");
                    this.translationVn = translated;
                    run.save();
                }
                json.put("status", "success");
                json.put("result", this.translationVn);
            }
        } catch (Exception e) {
            json.put("status", "error");
            json.put("message", e.getMessage());
        }
        rsp.getWriter().write(json.toString());
    }

    @POST
    public void doClearError(StaplerRequest req, StaplerResponse rsp) throws IOException, ServletException {
        run.checkPermission(Run.UPDATE);
        this.lastError = null;
        rsp.setContentType("application/json");
        rsp.getWriter().write("{\"status\":\"success\"}");
    }

    public String executeTranslation(String text, String targetLang) throws IOException {
        AiLogAnalyzerNotifier.DescriptorImpl descriptor = ExtensionList.lookupSingleton(AiLogAnalyzerNotifier.DescriptorImpl.class);
        String endpointUrl = descriptor.getApiEndpointUrl();
        Secret apiKey = descriptor.getApiKey();

        if (endpointUrl == null || endpointUrl.isEmpty()) {
            throw new IOException("API Endpoint URL is not configured globally.");
        }

        String modelToUse = getAiModel();
        if (modelToUse == null || modelToUse.equals("autodetect")) {
            List<String> detected = autodetectModelsStatic(endpointUrl, apiKey, null);
            if (!detected.isEmpty()) {
                modelToUse = detected.get(0);
            } else {
                modelToUse = "gemini-1.5-pro";
            }
        }

        String prompt = "You are a professional translator. Translate the following Markdown text into Vietnamese. Keep the exact Markdown layout, headings, tables, code blocks, syntax, and structure. Do not translate code blocks or log snippets, but translate headings, descriptions, and details. Do not omit any details. Only return the translated Markdown text without any introduction or markdown wrapping blocks.";
        
        org.json.JSONObject payload = new org.json.JSONObject();
        payload.put("model", modelToUse);

        org.json.JSONArray messages = new org.json.JSONArray();
        org.json.JSONObject systemMessage = new org.json.JSONObject();
        systemMessage.put("role", "system");
        systemMessage.put("content", prompt);
        messages.put(systemMessage);

        org.json.JSONObject userMessage = new org.json.JSONObject();
        userMessage.put("role", "user");
        userMessage.put("content", text);
        messages.put(userMessage);

        payload.put("messages", messages);

        URL url = new URL(endpointUrl);
        HttpURLConnection conn = (HttpURLConnection) url.openConnection();
        conn.setRequestMethod("POST");
        conn.setRequestProperty("Content-Type", "application/json");
        conn.setRequestProperty("Accept", "application/json");
        if (apiKey != null && !apiKey.getPlainText().isEmpty()) {
            conn.setRequestProperty("Authorization", "Bearer " + apiKey.getPlainText());
            conn.setRequestProperty("x-api-key", apiKey.getPlainText());
        }
        conn.setConnectTimeout(30000);
        conn.setReadTimeout(30000);
        conn.setDoOutput(true);

        try (OutputStream os = conn.getOutputStream()) {
            byte[] input = payload.toString().getBytes(StandardCharsets.UTF_8);
            os.write(input, 0, input.length);
        }

        int responseCode = conn.getResponseCode();
        if (responseCode >= 200 && responseCode < 300) {
            StringBuilder responseBuilder = new StringBuilder();
            try (BufferedReader br = new BufferedReader(new InputStreamReader(conn.getInputStream(), StandardCharsets.UTF_8))) {
                String responseLine;
                while ((responseLine = br.readLine()) != null) {
                    responseBuilder.append(responseLine.trim());
                }
            }
            
            String rawResponse = responseBuilder.toString();
            try {
                org.json.JSONObject jsonResponse = new org.json.JSONObject(rawResponse);
                if (jsonResponse.has("choices")) {
                    org.json.JSONArray choices = jsonResponse.getJSONArray("choices");
                    if (choices.length() > 0) {
                        org.json.JSONObject firstChoice = choices.getJSONObject(0);
                        if (firstChoice.has("message")) {
                            org.json.JSONObject msgObj = firstChoice.getJSONObject("message");
                            if (msgObj.has("content")) {
                                return msgObj.getString("content");
                            }
                        }
                    }
                }
                if (jsonResponse.has("analysis")) {
                    return jsonResponse.getString("analysis");
                }
                return rawResponse;
            } catch (Exception e) {
                return rawResponse;
            }
        } else {
            throw new IOException("Translation API request failed with HTTP " + responseCode);
        }
    }

    @POST
    public void doGetAvailableModels(StaplerRequest req, StaplerResponse rsp) throws IOException, ServletException {
        run.checkPermission(Run.UPDATE);
        rsp.setContentType("application/json");

        net.sf.json.JSONObject json = new net.sf.json.JSONObject();
        try {
            AiLogAnalyzerNotifier.DescriptorImpl descriptor = ExtensionList.lookupSingleton(AiLogAnalyzerNotifier.DescriptorImpl.class);
            String endpointUrl = descriptor.getApiEndpointUrl();
            Secret apiKey = descriptor.getApiKey();

            List<String> models = new ArrayList<>();
            if (endpointUrl != null && !endpointUrl.isEmpty()) {
                models = autodetectModelsStatic(endpointUrl, apiKey, null);
            }
            
            net.sf.json.JSONArray modelsArray = new net.sf.json.JSONArray();
            for (String m : models) {
                modelsArray.add(m);
            }

            json.put("status", "success");
            json.put("models", modelsArray);
        } catch (Exception e) {
            json.put("status", "error");
            json.put("message", e.getMessage());
        }
        rsp.getWriter().write(json.toString());
    }

    public static List<String> autodetectModelsStatic(String endpointUrl, Secret apiKey, PrintStream logger) {
        String modelsUrl = endpointUrl;
        if (modelsUrl.endsWith("/")) {
            modelsUrl = modelsUrl.substring(0, modelsUrl.length() - 1);
        }
        if (modelsUrl.endsWith("/chat/completions")) {
            modelsUrl = modelsUrl.replace("/chat/completions", "/models");
        } else if (modelsUrl.endsWith("/v1/chat/completions")) {
            modelsUrl = modelsUrl.replace("/v1/chat/completions", "/v1/models");
        } else if (modelsUrl.endsWith("/v1/chat")) {
            modelsUrl = modelsUrl.replace("/v1/chat", "/v1/models");
        } else if (!modelsUrl.endsWith("/models")) {
            modelsUrl = modelsUrl + "/models";
        }
        
        if (logger != null) {
            logger.println("[AI Log Analyzer] Autodetecting models from: " + modelsUrl);
        }

        try {
            URL url = new URL(modelsUrl);
            HttpURLConnection conn = (HttpURLConnection) url.openConnection();
            conn.setRequestMethod("GET");
            conn.setRequestProperty("Accept", "application/json");
            if (apiKey != null && !apiKey.getPlainText().isEmpty()) {
                conn.setRequestProperty("Authorization", "Bearer " + apiKey.getPlainText());
                conn.setRequestProperty("x-api-key", apiKey.getPlainText());
            }
            conn.setConnectTimeout(10000); // 10 seconds
            conn.setReadTimeout(10000);

            int responseCode = conn.getResponseCode();
            if (responseCode >= 200 && responseCode < 300) {
                StringBuilder responseBuilder = new StringBuilder();
                try (BufferedReader br = new BufferedReader(new InputStreamReader(conn.getInputStream(), StandardCharsets.UTF_8))) {
                    String responseLine;
                    while ((responseLine = br.readLine()) != null) {
                        responseBuilder.append(responseLine.trim());
                    }
                }
                
                String rawResponse = responseBuilder.toString();
                org.json.JSONObject jsonResponse = new org.json.JSONObject(rawResponse);
                List<String> detectedModels = new ArrayList<>();
                
                if (jsonResponse.has("data")) {
                    org.json.JSONArray dataArray = jsonResponse.getJSONArray("data");
                    for (int i = 0; i < dataArray.length(); i++) {
                        org.json.JSONObject modelObj = dataArray.getJSONObject(i);
                        if (modelObj.has("id")) {
                            detectedModels.add(modelObj.getString("id"));
                        }
                    }
                } else if (jsonResponse.has("models")) {
                    org.json.JSONArray modelsArray = jsonResponse.getJSONArray("models");
                    for (int i = 0; i < modelsArray.length(); i++) {
                        org.json.JSONObject modelObj = modelsArray.getJSONObject(i);
                        if (modelObj.has("name")) {
                            String name = modelObj.getString("name");
                            if (name.startsWith("models/")) {
                                name = name.substring(7);
                            }
                            detectedModels.add(name);
                        }
                    }
                }
                
                if (!detectedModels.isEmpty()) {
                    if (logger != null) {
                        logger.println("[AI Log Analyzer] Autodetected models: " + String.join(", ", detectedModels));
                    }
                    return detectedModels;
                }
            } else {
                if (logger != null) {
                    logger.println("[AI Log Analyzer] Autodetect models failed with HTTP code: " + responseCode);
                }
            }
        } catch (Exception e) {
            if (logger != null) {
                logger.println("[AI Log Analyzer] Autodetect models failed: " + e.getMessage());
            }
        }
        return Collections.emptyList();
    }
}
