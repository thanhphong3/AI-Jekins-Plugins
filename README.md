# AI Log Analyzer Jenkins Plugin

This custom Jenkins plugin automatically analyzes failed or unstable build logs using an external AI API (e.g. Gameloft AI) and displays the root cause directly on the Jenkins Build Summary page.

## Features

1. **Global Configuration:** Securely configure the API Endpoint URL and API Key (Bearer token).
2. **Job Configuration:** Set maximum number of log lines to extract (default 500) and provide a custom prompt.
3. **Smart Trigger:** Only runs when a build is `FAILURE` or `UNSTABLE`.
4. **UI Integration:** The AI's analysis is injected into the Build Summary page for immediate visibility, avoiding the need to dig through raw console logs.

## Prerequisites

- Java 11 or higher
- Maven 3.x
- Jenkins 2.361.4 or higher

## Build Instructions

1. Open a terminal/command prompt and navigate to the directory containing the `pom.xml`.
2. Run the following Maven command:
   ```bash
   mvn clean package
   ```
3. Once the build is successful, you will find the generated Jenkins plugin file at:
   ```
   target/ai-log-analyzer.hpi
   ```

## Installation Instructions

1. Log in to your Jenkins Dashboard as an Administrator.
2. Go to **Manage Jenkins** -> **Manage Plugins** (or **Plugins** in newer versions).
3. Click on the **Advanced settings** tab.
4. Scroll down to the **Deploy Plugin** section.
5. Choose the `target/ai-log-analyzer.hpi` file from your computer and click **Deploy**.
6. Restart Jenkins if prompted.

## Configuration

### Global Configuration

1. Go to **Manage Jenkins** -> **Configure System** (or **System**).
2. Scroll down to find the **AI Log Analyzer (Gameloft AI)** section.
3. Enter your AI **API Endpoint URL**.
4. Enter your **API Key** (it will be stored securely).
5. Click **Save**.

### Job Configuration

1. Open any Freestyle or Pipeline job.
2. Go to **Configure**.
3. Under **Post-build Actions**, click **Add post-build action** and select **AI Log Analyzer (Gameloft AI)**.
4. (Optional) Adjust the **Max Log Lines to Extract** (default 500).
5. (Optional) Adjust the **Custom Prompt Prefix** to guide the AI.
6. Click **Save**.

## How it works

When a build fails or becomes unstable, the plugin reads the specified number of lines from the end of the console log. It sends this snippet alongside the custom prompt as a JSON payload to the configured API.

Assuming the AI API returns a JSON response, the plugin extracts the analysis and displays it beautifully on the Build Summary page under the **AI Log Analysis** section.
