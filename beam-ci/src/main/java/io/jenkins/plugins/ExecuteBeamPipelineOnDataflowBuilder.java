package io.jenkins.plugins;

import hudson.Launcher;
import hudson.Extension;
import hudson.FilePath;
import hudson.util.FormValidation;
import hudson.model.AbstractProject;
import hudson.model.Run;
import hudson.model.TaskListener;
import hudson.tasks.Builder;
import hudson.tasks.BuildStepDescriptor;
import jenkins.util.SystemProperties;
import org.kohsuke.stapler.DataBoundConstructor;
import org.kohsuke.stapler.QueryParameter;

import javax.servlet.ServletException;
import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.io.InputStreamReader;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;

import jenkins.tasks.SimpleBuildStep;

public class ExecuteBeamPipelineOnDataflowBuilder extends Builder implements SimpleBuildStep {

    private final String pathToCreds;
    private final String pathToMainClass;
    private final String pipelineOptions;
    private final String buildReleaseOptions;
    private boolean useJava; // if false, use Python
    private boolean useGradle; // if false, use Maven

    @DataBoundConstructor
    public ExecuteBeamPipelineOnDataflowBuilder(String pathToCreds, String pathToMainClass, String pipelineOptions, String buildReleaseOptions, boolean useJava, boolean useGradle) {
        this.pathToCreds = pathToCreds;
        this.pathToMainClass = pathToMainClass;
        this.pipelineOptions = pipelineOptions;
        this.buildReleaseOptions = buildReleaseOptions;
        this.useJava = useJava;
        this.useGradle = useGradle;
    }

    public String getPathToCreds() { return pathToCreds; }

    public String getPathToMainClass() {
        return pathToMainClass;
    }

    public String getPipelineOptions() {
        return pipelineOptions;
    }

    public String getBuildReleaseOptions() {
        return buildReleaseOptions;
    }

    public boolean getUseJava() {
        return useJava;
    }

    public boolean getUseGradle() {
        return useGradle;
    }

    private void buildCommand(String workspace, ProcessBuilder processBuilder) {
        ArrayList<String> command;
        if (this.useJava) {
            String pipelineOptions = this.pipelineOptions.replaceAll("[\\t\\n]+"," ");
            if (this.useGradle) { // gradle
                command = new ArrayList<>(Arrays.asList("gradle", "clean", "execute", "-DmainClass=" + this.pathToMainClass, "-Dexec.args=" + pipelineOptions));
            } else { // maven
                command = new ArrayList<>(Arrays.asList("mvn", "compile", "exec:java", "-Dexec.mainClass=" + this.pathToMainClass, "-Dexec.args=" + pipelineOptions));
            }

            // add pipeline and build release options if included
            if (!this.buildReleaseOptions.equals("")) {
                String[] buildReleaseOptions = this.buildReleaseOptions.split("\\s+"); // split build release options by whitespace
                command.addAll(Arrays.asList(buildReleaseOptions)); // add build release options as separate list elements
            }
            //        System.out.println(Arrays.toString(command.toArray()));
            processBuilder.command(command);
        } else { // python
            // Execute Bash Script
            // todo figure out how to locate the bash script file from the workspace directory
            processBuilder.command("executePythonBeamPipeline.sh", workspace, this.pathToMainClass, this.pipelineOptions, this.buildReleaseOptions);
        }
    }

    @Override
    public void perform(Run<?, ?> run, FilePath workspace, Launcher launcher, TaskListener listener) throws InterruptedException, IOException {
        ProcessBuilder processBuilder = new ProcessBuilder();

        // see that all configurations are received correctly
        listener.getLogger().println("path to google app creds : " + this.pathToCreds);
        listener.getLogger().println("path to main class : " + this.pathToMainClass);
        listener.getLogger().println("pipeline options : " + this.pipelineOptions);
        listener.getLogger().println("build release options : " + this.buildReleaseOptions);
        listener.getLogger().println("use java: " + this.useJava);
        listener.getLogger().println("use gradle: " + this.useGradle);

        Map<String, String> env = processBuilder.environment();
        env.put("GOOGLE_APPLICATION_CREDENTIALS", this.pathToCreds);

        // set correct directory to be running command
        processBuilder.directory(new File(workspace.toURI()));
        listener.getLogger().println("workspace : " + workspace.toURI().toString());

        // build and set command to processBuilder based on configurations
        buildCommand(workspace.toURI().toString(), processBuilder);
        Process process = processBuilder.start();

        BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream()));
        String line;
        while((line = reader.readLine()) != null) {
            listener.getLogger().println(line);
        }

        BufferedReader reader2 = new BufferedReader(new InputStreamReader(process.getErrorStream()));
        while((line = reader2.readLine()) != null) {
            listener.getLogger().println(line);
        }

        int exitCode = process.waitFor();
        listener.getLogger().println("\n Exited with error code : " + exitCode);
    }

    @Extension
    public static final class DescriptorImpl extends BuildStepDescriptor<Builder> {

        public FormValidation doCheckPathToMainClass(@QueryParameter String value) {
            if (value.length() == 0)
                return FormValidation.error("Missing path to main class.");
            return FormValidation.ok();
        }

        @Override
        public boolean isApplicable(Class<? extends AbstractProject> aClass) {
            return true;
        }

        @Override
        public String getDisplayName() {
            return "Execute Beam Pipeline on Dataflow";
        }

    }

}
