package com.dtolabs.rundeck.plugin.jcifs;


import com.dtolabs.rundeck.core.Constants;
import com.dtolabs.rundeck.core.common.Framework;
import com.dtolabs.rundeck.core.common.INodeEntry;
import com.dtolabs.rundeck.core.dispatcher.DataContextUtils;
import com.dtolabs.rundeck.core.execution.ExecutionContext;
import com.dtolabs.rundeck.core.execution.impl.common.BaseFileCopier;
import com.dtolabs.rundeck.core.execution.script.ScriptfileUtils;
import com.dtolabs.rundeck.core.execution.service.DestinationFileCopier;
import com.dtolabs.rundeck.core.execution.service.FileCopier;
import com.dtolabs.rundeck.core.execution.service.FileCopierException;
import com.dtolabs.rundeck.core.execution.workflow.steps.FailureReason;
import com.dtolabs.rundeck.core.execution.workflow.steps.StepFailureReason;
import com.dtolabs.rundeck.core.plugins.Plugin;
import com.dtolabs.rundeck.core.plugins.configuration.Describable;
import com.dtolabs.rundeck.core.plugins.configuration.Description;
import com.dtolabs.rundeck.core.plugins.configuration.StringRenderingConstants;
import com.dtolabs.rundeck.core.storage.ResourceMeta;
import com.dtolabs.rundeck.core.tasks.net.SSHTaskBuilder;
import com.dtolabs.rundeck.core.plugins.configuration.ConfigurationException;
import com.dtolabs.rundeck.plugins.util.DescriptionBuilder;
import com.dtolabs.rundeck.plugins.util.PropertyBuilder;

import jcifs.smb.NtlmPasswordAuthentication;
import jcifs.smb.SmbFile;
import jcifs.smb.SmbFileInputStream;
import jcifs.smb.SmbFileOutputStream;
import jcifs.smb.SmbException;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.net.MalformedURLException;
import java.net.UnknownHostException;
import java.nio.file.Files;

import org.apache.tools.ant.BuildException;
import org.apache.tools.ant.Project;
import org.apache.tools.ant.Task;
import org.apache.tools.ant.taskdefs.Echo;
import org.apache.tools.ant.taskdefs.Sequential;
import org.rundeck.storage.api.Path;
import org.rundeck.storage.api.PathUtil;
import org.rundeck.storage.api.StorageException;


/**
 * JcifsFileCopier is ...
 *
 * @author Espen Blikstad <a href="mailto:espen@blikstad.no">espen@blikstad.no</a>
 */
@Plugin(name = JcifsFileCopier.SERVICE_PROVIDER_TYPE, service = "FileCopier")
public class JcifsFileCopier extends BaseFileCopier implements FileCopier, Describable, DestinationFileCopier {
    public static final String SERVICE_PROVIDER_TYPE = "jcifs";
    private static final String CONFIG_PASSWORD_STORAGE_PATH = "passwordStoragePath";
    public static final String JCIFS_PASSWORD_STORAGE_PATH = "jcifs-password-storage-path";
    public static final String JCIFS_USER = "jcifs-user";

    
    private static final String PROJ_PROP_PREFIX = "project.";
    private static final String FWK_PROP_PREFIX = "framework.";


    static final Description DESC = DescriptionBuilder.builder()
        .name(SERVICE_PROVIDER_TYPE)
        .title("JCIFS")
        .description("Copies a script file to a remote node via CIFS.")
        .property(
                PropertyBuilder.builder()
                               .string(CONFIG_PASSWORD_STORAGE_PATH)
                               .title("Password Storage")
                               .description(
                                       "Key Storage Path for winrm Password.\n\n" +
                                       "The path can contain property references like `${node.name}`."
                               )
                               .renderingOption(
                                       StringRenderingConstants.SELECTION_ACCESSOR_KEY,
                                       StringRenderingConstants.SelectionAccessor.STORAGE_PATH
                               )
                               .renderingOption(
                                       StringRenderingConstants.STORAGE_PATH_ROOT_KEY,
                                       "keys"
                               )
                               .renderingOption(
                                       StringRenderingConstants.STORAGE_FILE_META_FILTER_KEY,
                                       "Rundeck-data-type=password"
                               )
                               .build()
      )
        .mapping(CONFIG_PASSWORD_STORAGE_PATH, PROJ_PROP_PREFIX + JCIFS_PASSWORD_STORAGE_PATH)
        .build();


    public Description getDescription() {
        return DESC;
    }


    private Framework framework;
    private String frameworkProject;

    public Framework getFramework() {
        return framework;
    }

    public String getFrameworkProject() {
        return frameworkProject;
    }


    static String nonBlank(String input) {
        if (null == input || "".equals(input.trim())) {
            return null;
        } else {
            return input.trim();
        }
    }
    /**
     * Resolve a node/project/framework property by first checking node attributes named X, then project properties
     * named "project.X", then framework properties named "framework.X". If none of those exist, return the default
     * value
     */
    private static String resolveProperty(
            final String nodeAttribute,
            final String defaultValue,
            final INodeEntry node,
            final String frameworkProject,
            final Framework framework
    )
    {
        if (null != node.getAttributes().get(nodeAttribute)) {
            return node.getAttributes().get(nodeAttribute);
        } else if (
                framework.hasProjectProperty(PROJ_PROP_PREFIX + nodeAttribute, frameworkProject)
                && !"".equals(framework.getProjectProperty(frameworkProject, PROJ_PROP_PREFIX + nodeAttribute))
                ) {
            return framework.getProjectProperty(frameworkProject, PROJ_PROP_PREFIX + nodeAttribute);
        } else if (framework.hasProperty(FWK_PROP_PREFIX + nodeAttribute)) {
            return framework.getProperty(FWK_PROP_PREFIX + nodeAttribute);
        } else {
            return defaultValue;
        }
    }
    

    public JcifsFileCopier(Framework framework) {
        this.framework = framework;
    }

    public String copyFileStream(final ExecutionContext context, InputStream input, INodeEntry node) throws
                                                                                                     FileCopierException {
    	context.getExecutionListener().log(Constants.DEBUG_LEVEL,
                "copyFileStream(final ExecutionContext context, InputStream input, INodeEntry node)");


        return copyFile(context, null, input, null, node);
    }

    public String copyFile(final ExecutionContext context, File scriptfile, INodeEntry node) throws
                                                                                             FileCopierException {
    	context.getExecutionListener().log(Constants.DEBUG_LEVEL,
                "copyFile(final ExecutionContext context, File scriptfile, INodeEntry node)");

        return copyFile(context, scriptfile, null, null, node);
    }

    public String copyScriptContent(ExecutionContext context, String script, INodeEntry node) throws
                                                                                              FileCopierException {
    	context.getExecutionListener().log(Constants.DEBUG_LEVEL,
                "copyScriptContent(ExecutionContext context, String script, INodeEntry node)");

        return copyFile(context, null, null, script, node);
    }


    private String copyFile(final ExecutionContext context, final File scriptfile, final InputStream input,
                            final String script, final INodeEntry node) throws FileCopierException {
        return copyFile(context, scriptfile, input, script, node, null);

    }

    private String getUsername(final INodeEntry node) {
        final String user;
        if (null != nonBlank(node.getUsername()) || node.containsUserName()) {
            user = nonBlank(node.getUsername());
            System.err.println("getUsername not null");

        } else {
            System.err.println("get jcifs-user");
            user = resolveProperty(JCIFS_USER, null, node, getFrameworkProject(), getFramework());
        }

        //if (null != user && user.contains("${")) {
        //    return DataContextUtils.replaceDataReferences(user, getContext().getDataContext());
        //}
        System.err.println("user: " + user);
        return user;
    }
    
    private String getPassword(final ExecutionContext context, final INodeEntry node)  throws ConfigurationException{
        //look for storage option
        System.err.println("resolveProperty: JCIFS_PASSWORD_STORAGE_PATH");
    	
        String storagePath = resolveProperty(JCIFS_PASSWORD_STORAGE_PATH, null,
                node, getFrameworkProject(), getFramework());
        System.err.println("storagePath: " + storagePath);
        if(null!=storagePath){
            //look up storage value
            if (storagePath.contains("${")) {
                storagePath = DataContextUtils.replaceDataReferences(
                        storagePath,
                        context.getDataContext()
                );
            }
            Path path = PathUtil.asPath(storagePath);
            System.err.println("path: " + path.getPath());
            
            try {
                ResourceMeta contents = context.getStorageTree().getResource(path)
                        .getContents();
                ByteArrayOutputStream byteArrayOutputStream = new ByteArrayOutputStream();
                contents.writeContent(byteArrayOutputStream);
                return new String(byteArrayOutputStream.toByteArray());
            } catch (StorageException e) {
                throw new ConfigurationException("Failed to read the JCIFS password for " +
                        "storage path: " + storagePath + ": " + e.getMessage());
            } catch (IOException e) {
                throw new ConfigurationException("Failed to read the JCIFS password for " +
                        "storage path: " + storagePath + ": " + e.getMessage());
            }
        }
        //else look up option value
       // final String passwordOption = resolveProperty(WINRM_PASSWORD_OPTION,
       //         DEFAULT_WINRM_PASSWORD_OPTION, getNode(),
       //         getFrameworkProject(), getFramework());
       // return evaluateSecureOption(passwordOption, getContext());
        return null;
    }


    private String copyFile(
            final ExecutionContext context,
            final File scriptfile,
            final InputStream input,
            final String script,
            final INodeEntry node,
            final String destinationPath
    ) throws FileCopierException {

        //Project project = new Project();

        final String remotefile;
        if(null==destinationPath) {
            remotefile = generateRemoteFilepathForNode(node, (null != scriptfile ? scriptfile.getName()
                    : "dispatch-script"));
        }else {
            remotefile = destinationPath;
        }
        //write to a local temp file or use the input file
        final File localTempfile =
                null != scriptfile ?
                        scriptfile :
                        writeTempFile(
                                context,
                                scriptfile,
                                input,
                                script
                        );

        
        

        final String username;
        username = getUsername(node);
        System.err.println("112 username: " + username);

        String password = null;
        
        frameworkProject = context.getFrameworkProject();
        System.err.println("try password");
        try{
        password = getPassword(context, node);
        }catch(ConfigurationException E){
            System.err.println("ouch");
        	
        }

        System.err.println("password: " + password);

        System.err.println("auth");
        NtlmPasswordAuthentication auth = new NtlmPasswordAuthentication(null, username, password);

        System.err.println("remotefile: " + remotefile);
        System.err.println("localTempfile: " + localTempfile);

        String smbfile = remotefile.replace(':', '$');
        System.err.println("smbfile:" + smbfile);
        
        smbfile = smbfile.replace('\\', '/');
        System.err.println("smbfile:" + smbfile);
        smbfile = "smb://" + node.getHostname() + "/" + smbfile;		
        System.err.println("smbfile:" + smbfile);
        
        
        SmbFileOutputStream destination = null;
        SmbFile test23 = null;
        
        try{
            System.err.println("new SmbFile");
        	
        test23 = new SmbFile(smbfile, auth);
        System.err.println("createNewFile");
        test23.createNewFile();
        

        }
        catch(MalformedURLException E){
        }
        catch(SmbException E){
        }

        
        /**
         * Copy the file over
         */
        context.getExecutionListener().log(3,"copying file: '" + localTempfile.getAbsolutePath()
                + "' to: '" + node.getNodename() + ":" + remotefile + "'");

        try{
            destination = new SmbFileOutputStream(test23);        	

        }
        catch(UnknownHostException E) {	
        }
        catch(MalformedURLException E) {	
        }
        catch(SmbException E) {	
        }
        
        
        try{
            Files.copy(localTempfile.toPath(), destination);
            destination.flush();
            destination.close();
        }
        catch (IOException e) {
            //throw new FileCopierException("[jcifs] Failed copying the file: " + errormsg, failureReason, e);
            throw new FileCopierException("[jcifs] Failed copying the file", StepFailureReason.Unknown, e);            
        }
        if (!localTempfile.delete()) {
            context.getExecutionListener().log(Constants.WARN_LEVEL,
                    "Unable to remove local temp file: " + localTempfile.getAbsolutePath());
        }
        return remotefile;
        
    }

    public String copyFileStream(ExecutionContext context, InputStream input, INodeEntry node,
            String destination) throws FileCopierException {
    	context.getExecutionListener().log(Constants.DEBUG_LEVEL,
                "copyFileStream(ExecutionContext context, InputStream input, INodeEntry node, String destination): " + destination);
        return copyFile(context, null, input, null, node, destination);
    }

    public String copyFile(ExecutionContext context, File file, INodeEntry node,
            String destination) throws FileCopierException {
    	context.getExecutionListener().log(Constants.DEBUG_LEVEL,
                "copyFile(ExecutionContext context, File file, INodeEntry node, String destination): " + destination);
        return copyFile(context, file, null, null, node, destination);
    }

    public String copyScriptContent(ExecutionContext context, String script, INodeEntry node,
            String destination) throws FileCopierException {
    	context.getExecutionListener().log(Constants.DEBUG_LEVEL,
                "copyScriptContent(ExecutionContext context, String script, INodeEntry node, String destination)" + destination);
        return copyFile(context, null, null, script, node, destination);
    }
}
