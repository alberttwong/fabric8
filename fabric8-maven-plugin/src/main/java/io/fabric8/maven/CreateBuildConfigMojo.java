/*
 * Copyright 2005-2015 Red Hat, Inc.                                    
 *                                                                      
 * Red Hat licenses this file to you under the Apache License, version  
 * 2.0 (the "License"); you may not use this file except in compliance  
 * with the License.  You may obtain a copy of the License at           
 *                                                                      
 *    http://www.apache.org/licenses/LICENSE-2.0                        
 *                                                                      
 * Unless required by applicable law or agreed to in writing, software  
 * distributed under the License is distributed on an "AS IS" BASIS,    
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or      
 * implied.  See the License for the specific language governing        
 * permissions and limitations under the License.
 */
package io.fabric8.maven;

import io.fabric8.devops.ProjectConfig;
import io.fabric8.devops.ProjectConfigs;
import io.fabric8.devops.connector.DevOpsConnector;
import io.fabric8.devops.connector.WebHooks;
import io.fabric8.kubernetes.api.Controller;
import io.fabric8.kubernetes.api.KubernetesClient;
import io.fabric8.kubernetes.api.ServiceNames;
import io.fabric8.letschat.LetsChatClient;
import io.fabric8.letschat.LetsChatKubernetes;
import io.fabric8.letschat.RoomDTO;
import io.fabric8.openshift.api.model.BuildConfig;
import io.fabric8.openshift.api.model.BuildConfigBuilder;
import io.fabric8.openshift.api.model.BuildConfigFluent;
import io.fabric8.repo.git.GitRepoClient;
import io.fabric8.repo.git.GitRepoKubernetes;
import io.fabric8.taiga.ModuleDTO;
import io.fabric8.taiga.ProjectDTO;
import io.fabric8.taiga.TaigaClient;
import io.fabric8.taiga.TaigaKubernetes;
import io.fabric8.taiga.TaigaModule;
import io.fabric8.utils.GitHelpers;
import io.fabric8.utils.Strings;
import io.fabric8.utils.URLUtils;
import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.plugin.MojoFailureException;
import org.apache.maven.plugins.annotations.Mojo;
import org.apache.maven.plugins.annotations.Parameter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.util.HashMap;
import java.util.Map;

/**
 * Creates an OpenShift BuildConfig object for a
 */
@Mojo(name = "create-build-config", requiresProject = false)
public class CreateBuildConfigMojo extends AbstractNamespacedMojo {

    /**
     * the current folder
     */
    @Parameter(defaultValue = "${basedir}")
    protected File basedir;

    /**
     * the gogs user name to use
     */
    @Parameter(property = "fabric8.gogsUsername")
    protected String username;

    /**
     * the gogs password
     */
    @Parameter(property = "fabric8.gogsPassword")
    protected String password;

    /**
     * the gogs branch to find the fabric8.yml if this goal is run outside of the source code
     */
    @Parameter(property = "fabric8.gogsBranch", defaultValue = "master")
    protected String branch;

    /**
     *
     */
    @Parameter(property = "fabric8.repoName", defaultValue = "${project.artifactId}")
    protected String repoName;

    /**
     *
     */
    @Parameter(property = "fabric8.fullName")
    protected String fullName;

    /**
     *
     */
    @Parameter(property = "fabric8.gitUrl")
    protected String gitUrl;

    /**
     * The webhook secret used for generic and github webhooks
     */
    @Parameter(property = "fabric8.webhookSecret", defaultValue = "secret101")
    protected String secret;

    /**
     * the build image stream name
     */
    @Parameter(property = "fabric8.buildImageStream", defaultValue = "triggerJenkins")
    protected String buildImageStream;

    /**
     * the build image stream tag
     */
    @Parameter(property = "fabric8.buildImageTag", defaultValue = "latest")
    protected String buildImageTag;

    /**
     * The name of the jenkins job to link to as the first job in the pipeline
     */
    @Parameter(property = "fabric8.jenkinsJob")
    protected String jenkinsJob;

    /**
     * The name of the jenkins monitor view
     */
    @Parameter(property = "fabric8.jenkinsMonitorView")
    protected String jenkinsMonitorView;

    /**
     * The name of the jenkins pipline view
     */
    @Parameter(property = "fabric8.jenkinsPipelineView")
    protected String jenkinsPipelineView;

    /**
     * The name of the taiga project name to use
     */
    @Parameter(property = "fabric8.tagiaProjectName", defaultValue = "${fabric8.repoName}")
    protected String taigaProjectName;

    /**
     * The slug name of the project in Taiga or will be auto-generated from the user and project name if not configured
     */
    @Parameter(property = "fabric8.taigaProjectSlug")
    protected String taigaProjectSlug;

    /**
     * The project page to link to
     */
    @Parameter(property = "fabric8.taigaProjectLinkPage", defaultValue = "backlog")
    protected String taigaProjectLinkPage;

    /**
     * The label for the issue tracker/kanban/scrum taiga project link
     */
    @Parameter(property = "fabric8.taigaProjectLinkLabel", defaultValue = "Backlog")
    protected String taigaProjectLinkLabel;

    /**
     * The team page to link to
     */
    @Parameter(property = "fabric8.taigaTeamLinkPage", defaultValue = "team")
    protected String taigaTeamLinkPage;

    /**
     * The label for the team page
     */
    @Parameter(property = "fabric8.taigaTeamLinkLabel", defaultValue = "Team")
    protected String taigaTeamLinkLabel;

    /**
     * Should we auto-create projects in taiga if they are missing?
     */
    @Parameter(property = "fabric8.taigaAutoCreate", defaultValue = "true")
    protected boolean taigaAutoCreate;

    /**
     * Should we enable Taiga integration
     */
    @Parameter(property = "fabric8.taigaEnabled", defaultValue = "true")
    protected boolean taigaEnabled;

    /**
     * Should we enable LetsChat integration if the
     * {@link LetsChatKubernetes#LETSCHAT_HUBOT_TOKEN} environment variable is enabled
     */
    @Parameter(property = "fabric8.letschatEnabled", defaultValue = "true")
    protected boolean letschatEnabled;

    /**
     * The label for the chat room page
     */
    @Parameter(property = "fabric8.letschatRoomLinkLabel", defaultValue = "Room")
    protected String letschatRoomLinkLabel;

    /**
     * The expression used to define the room name for this project; using expressions like
     * <code>${namespace}</code> or <code>${repoName}</code> to replace project specific values
     *
     */
    @Parameter(property = "fabric8.letschatRoomLinkLabel", defaultValue = "fabric8_${namespace}")
    protected String letschatRoomExpression;



    @Override
    public void execute() throws MojoExecutionException, MojoFailureException {
        KubernetesClient kubernetes = getKubernetes();
        DevOpsConnector connector = new DevOpsConnector();

        connector.setKubernetes(kubernetes);
        connector.setUsername(username);
        connector.setPassword(password);
        connector.setBranch(branch);
        connector.setBasedir(basedir);
        connector.setRepoName(repoName);
        connector.setFullName(fullName);
        connector.setGitUrl(gitUrl);
        connector.setSecret(secret);
        connector.setBuildImageStream(buildImageStream);
        connector.setBuildImageTag(buildImageTag);
        connector.setJenkinsJob(jenkinsJob);
        connector.setJenkinsMonitorView(jenkinsMonitorView);
        connector.setJenkinsPipelineView(jenkinsPipelineView);

        connector.setTaigaProjectName(taigaProjectName);
        connector.setTaigaProjectSlug(taigaProjectSlug);
        connector.setTaigaProjectLinkPage(taigaProjectLinkPage);
        connector.setTaigaProjectLinkLabel(taigaProjectLinkLabel);
        connector.setTaigaTeamLinkPage(taigaTeamLinkPage);
        connector.setTaigaTeamLinkLabel(taigaTeamLinkLabel);
        connector.setTaigaAutoCreate(taigaAutoCreate);
        connector.setTaigaEnabled(taigaEnabled);

        connector.setLetschatEnabled(letschatEnabled);
        connector.setLetschatRoomLinkLabel(letschatRoomLinkLabel);
        connector.setLetschatRoomExpression(letschatRoomExpression);

        getLog().debug("Using connector: " + connector);

        try {
            connector.execute();
        } catch (Exception e) {
            getLog().error("Failed to update DevOps resources: " + e, e);
            throw new MojoExecutionException("Failed to update DevOps resources: " + e, e);
        }
    }


}
