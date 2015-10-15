package io.github.collaboratory;

import com.google.common.base.Joiner;
import com.google.common.base.Optional;
import org.apache.commons.cli.CommandLine;
import org.apache.commons.cli.CommandLineParser;
import org.apache.commons.cli.DefaultParser;
import org.apache.commons.cli.Options;
import org.apache.commons.cli.ParseException;
import org.apache.commons.configuration.ConfigurationException;
import org.apache.commons.configuration.HierarchicalINIConfiguration;
import org.apache.commons.exec.DefaultExecuteResultHandler;
import org.apache.commons.exec.DefaultExecutor;
import org.apache.commons.exec.ExecuteException;
import org.apache.commons.exec.Executor;
import org.apache.commons.exec.PumpStreamHandler;
import org.apache.commons.io.FileUtils;
import org.apache.commons.io.output.TeeOutputStream;
import org.apache.commons.lang3.tuple.ImmutablePair;
import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.apache.commons.vfs2.FileObject;
import org.apache.commons.vfs2.FileSystemException;
import org.apache.commons.vfs2.FileSystemManager;
import org.apache.commons.vfs2.Selectors;
import org.apache.commons.vfs2.VFS;
import org.json.simple.JSONObject;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.composer.ComposerException;
import org.yaml.snakeyaml.constructor.SafeConstructor;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.OutputStream;
import java.io.UnsupportedEncodingException;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * @author boconnor 9/24/15
 * @author dyuen
 * @author tetron
 */
public class LauncherCWL {

    private static final Log LOG = LogFactory.getLog(LauncherCWL.class);
    private final String configFilePath;
    private final String imageDescriptorPath;
    private final String runtimeDescriptorPath;
    private HierarchicalINIConfiguration config = null;
    private String globalWorkingDir = null;
    private final Yaml yaml = new Yaml(new SafeConstructor());
    private final Optional<OutputStream> stdoutStream;
    private final Optional<OutputStream> stderrStream;

    /**
     * Constructor for shell-based launch
     * @param args raw arguments from the command-line
     */
    public LauncherCWL(String[] args) {
        // create the command line parser
        CommandLineParser parser = new DefaultParser();
        // parse command line
        CommandLine line = parseCommandLine(parser, args);
        this.configFilePath = line.getOptionValue("config");
        this.imageDescriptorPath = line.getOptionValue("descriptor");
        this.runtimeDescriptorPath = line.getOptionValue("job");
        // do not forward stdout and stderr
        stdoutStream = Optional.absent();
        stderrStream = Optional.absent();
    }

    /**
     * Constructor for programmatic launch
     * @param configFilePath configuration for this launcher
     * @param imageDescriptorPath descriptor for the tool itself
     * @param runtimeDescriptorPath descriptor for this run of the tool
     */
    public LauncherCWL(String configFilePath, String imageDescriptorPath, String runtimeDescriptorPath, OutputStream stdoutStream, OutputStream stderrStream){
        this.configFilePath = configFilePath;
        this.imageDescriptorPath = imageDescriptorPath;
        this.runtimeDescriptorPath = runtimeDescriptorPath;
        // programmatically forward stdout and stderr
        this.stdoutStream = Optional.of(stdoutStream);
        this.stderrStream = Optional.of(stderrStream);
    }

    public void run(){
        // now read in the INI file
        try {
            config = new HierarchicalINIConfiguration(configFilePath);
        } catch (ConfigurationException e) {
            throw new RuntimeException("could not read launcher config ini", e);
        }

        // parse the CWL tool definition without validation
        Map<String, Object> cwl = parseCWL(imageDescriptorPath, false);

        if (cwl == null) {
            LOG.info("CWL was null");
            return;
        }

        if (!(cwl.get("class")).equals("CommandLineTool")) {
            LOG.info("Must be CommandLineTool");
            return;
        }

        // this is the job parameterization, just a JSON, defines the inputs/outputs in terms or real URLs that are provisioned by the launcher
        Map<String, Map<String, Object>> inputsAndOutputsJson = loadJob(runtimeDescriptorPath);

        if (inputsAndOutputsJson == null) {
            LOG.info("Cannot load job object.");
            return;
        }

        // setup directories
        globalWorkingDir = setupDirectories();

        // pull input files
        final Map<String, Map<String, String>> inputsId2dockerMountMap = pullFiles(cwl, inputsAndOutputsJson);

        // prep outputs, just creates output dir and records what the local output path will be
        Map<String, Map<String, String>> outputMap = prepUploads(cwl, inputsAndOutputsJson);

        // create updated JSON inputs document
        String newJsonPath = createUpdatedInputsAndOutputsJson(inputsId2dockerMountMap, outputMap, inputsAndOutputsJson);

        // run validation now that the input is ready
        // parse the CWL tool definition without validation
        Map<String, Object> validCWL = parseCWL(imageDescriptorPath, true);

        if (validCWL == null) {
            LOG.info("CWL was null");
            return;
        }

        if (!(validCWL.get("class")).equals("CommandLineTool")) {
            LOG.info("Must be CommandLineTool");
            return;
        }

        // run command
        LOG.info("RUNNING COMMAND");
        Map<String, Object> outputObj = runCWLCommand(imageDescriptorPath, newJsonPath, globalWorkingDir + "/outputs/");

        // push output files
        pushOutputFiles(outputMap, outputObj);
    }

    private Map<String, Map<String, String>> prepUploads(Map<String, Object> cwl, Map<String, Map<String, Object>> inputsOutputs) {

        Map<String, Map<String, String>> fileMap = new HashMap<>();

        LOG.info("PREPPING UPLOADS...");

        LOG.info(((Map) cwl).get("outputs"));

        List<Map<String, Object>> files = (List) ((Map)cwl).get("outputs");

        // for each file input from the CWL
        for (Map<String, Object> file : files) {

            // pull back the name of the input from the CWL
            LOG.info(file.toString());
            String cwlID = ((String)((Map) file).get("id")).substring(1);
            LOG.info("ID: " + cwlID);


            // now that I have an input name from the CWL I can find it in the JSON parameterization for this run
            LOG.info("JSON: " + inputsOutputs.toString());
            for (String paramName : inputsOutputs.keySet()) {
                Map param = inputsOutputs.get(paramName);
                String path = (String)param.get("path");

                if (paramName.equals(cwlID)) {

                    // if it's the current one
                    LOG.info("PATH TO UPLOAD TO: " + path + " FOR " + cwlID + " FOR " + paramName);

                    // output
                    // TODO: poor naming here, need to cleanup the variables
                    // just file name
                    // the file URL
                    File filePathObj = new File(cwlID);
                    //String newDirectory = globalWorkingDir + "/outputs/" + UUID.randomUUID().toString();
                    String newDirectory = globalWorkingDir + "/outputs";
                    executeCommand("mkdir -p " + newDirectory);
                    File newDirectoryFile = new File(newDirectory);
                    String uuidPath = newDirectoryFile.getAbsolutePath() + "/" + filePathObj.getName();

                    // VFS call, see https://github.com/abashev/vfs-s3/tree/branch-2.3.x and
                    // https://commons.apache.org/proper/commons-vfs/filesystems.html

                    // now add this info to a hash so I can later reconstruct a docker -v command
                    HashMap<String, String> new1 = new HashMap<>();
                    new1.put("local_path", uuidPath);
                    new1.put("docker_path", cwlID);
                    new1.put("url", path);
                    fileMap.put(cwlID, new1);

                    LOG.info("UPLOAD FILE: LOCAL: " + cwlID + " URL: " + path);

                }
            }

        }
        return fileMap;
    }

    private String createUpdatedInputsAndOutputsJson(Map<String, Map<String, String>> fileMap, Map<String, Map<String, String>> outputMap, Map<String, Map<String, Object>> inputsAndOutputsJson) {

        JSONObject newJSON = new JSONObject();

        for (String paramName : inputsAndOutputsJson.keySet()) {
            Map<String, Object> param = inputsAndOutputsJson.get(paramName);
            String path = (String) param.get("path");
            LOG.info("PATH: " + path + " PARAM_NAME: " + paramName);
            // will be null for output
            if (fileMap.get(paramName) != null) {
                param.put("path", fileMap.get(paramName).get("local_path"));
                LOG.info("NEW FULL PATH: " + fileMap.get(paramName).get("local_path"));
            } else if (outputMap.get(paramName) != null) {
                param.put("path", outputMap.get(paramName).get("local_path"));
                LOG.info("NEW FULL PATH: " + outputMap.get(paramName).get("local_path"));
            }
            // now add to the new JSON structure
            JSONObject newRecord = new JSONObject();
            newRecord.put("class", param.get("class"));
            newRecord.put("path", param.get("path"));
            newJSON.put(paramName, newRecord);
        }

        writeJob("foo.json", newJSON);

        return("foo.json");
    }

    private Map<String, Map<String, Object>> loadJob(String jobPath) {
        try {
            return (Map<String, Map<String, Object>>)yaml.load(new FileInputStream(jobPath));
        } catch (FileNotFoundException e) {
            throw new RuntimeException("could not load job from yaml",e);
        }
    }

    private void writeJob(String jobOutputPath, JSONObject newJson) {
        try {
            //TODO: investigate, why is this replacement occurring?
            final String replace = newJson.toJSONString().replace("\\", "");
            FileUtils.writeStringToFile(new File(jobOutputPath), replace, StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new RuntimeException("Could not write job ", e);
        }
    }

    private String setupDirectories() {

        LOG.info("MAKING DIRECTORIES...");
        // directory to use, typically a large, encrypted filesystem
        String workingDir = config.getString("working-directory");
        // make UUID
        UUID uuid = UUID.randomUUID();
        // setup directories
        globalWorkingDir = workingDir + "/launcher-" + uuid.toString();
        executeCommand("mkdir -p " + workingDir + "/launcher-" + uuid.toString());
        executeCommand("mkdir -p " + workingDir + "/launcher-" + uuid.toString() + "/configs");
        executeCommand("mkdir -p " + workingDir + "/launcher-" + uuid.toString() + "/working");
        executeCommand("mkdir -p " + workingDir + "/launcher-" + uuid.toString() + "/inputs");
        executeCommand("mkdir -p " + workingDir + "/launcher-" + uuid.toString() + "/logs");
        executeCommand("mkdir -p " + workingDir + "/launcher-" + uuid.toString() + "/outputs");

        return (new File(workingDir + "/launcher-" + uuid.toString()).getAbsolutePath());

    }

    private Map<String, Object> runCWLCommand(String cwlFile, String jsonSettings, String workingDir) {
        String[] s = new String[]{"cwltool","--outdir", workingDir, cwlFile, jsonSettings};
        final ImmutablePair<String, String> execute = this.executeCommand(Joiner.on(" ").join(Arrays.asList(s)));
        Map<String, Object> obj = (Map<String, Object>)yaml.load(execute.getLeft());
        return obj;
    }

    private void pushOutputFiles(Map<String, Map<String, String>> fileMap, Map<String, Object> outputObject) {

        LOG.info("UPLOADING FILES...");

        for (String fileName : fileMap.keySet()) {
            Map file = fileMap.get(fileName);

            String cwlOutputPath = (String)((Map)((Map)outputObject).get(fileName)).get("path");

            LOG.info("NAME: " + file.get("local_path") + " URL: " + file.get("url") + " FILENAME: " + fileName + " CWL OUTPUT PATH: "
                    + cwlOutputPath);


            try {
                FileSystemManager fsManager;
                // trigger a copy from the URL to a local file path that's a UUID to avoid collision
                fsManager = VFS.getManager();
                FileObject dest = fsManager.resolveFile((String)file.get("url"));
                FileObject src = fsManager.resolveFile(new File(cwlOutputPath).getAbsolutePath());
                dest.copyFrom(src, Selectors.SELECT_SELF);
            } catch (FileSystemException e) {
                throw new RuntimeException("Could not provision output files", e);
            }


        }

    }

    /**
     * Execute a command and return stdout and stderr
     * @param command
     * @return
     */
    private ImmutablePair<String, String> executeCommand(String command) {
        LOG.info("CMD: " + command);
        // TODO: limit our output in case the called program goes crazy

        // these are for returning the output for use by this
        ByteArrayOutputStream localStdoutStream = new ByteArrayOutputStream();
        ByteArrayOutputStream localStdErrStream = new ByteArrayOutputStream();
        OutputStream stdout =  localStdoutStream;
        OutputStream stderr = localStdErrStream;
        if (this.stdoutStream.isPresent()){
            assert(this.stderrStream.isPresent());
            // in this branch, we want a copy of the output for Consonance
            stdout = new TeeOutputStream(localStdoutStream, this.stdoutStream.get());
            stderr = new TeeOutputStream(localStdErrStream, this.stderrStream.get());
        }

        DefaultExecuteResultHandler resultHandler = new DefaultExecuteResultHandler();
        String utf8 = StandardCharsets.UTF_8.name();
        try {
            final org.apache.commons.exec.CommandLine parse = org.apache.commons.exec.CommandLine.parse(command);
            Executor executor = new DefaultExecutor();
            executor.setExitValue(0);
            // get stdout and stderr
            executor.setStreamHandler(new PumpStreamHandler(stdout, stderr));
            executor.execute(parse, resultHandler);
            resultHandler.waitFor();
            // not sure why commons-exec does not throw an exception
            if (resultHandler.getExitValue() != 0){
                throw new ExecuteException("could not run command: " + command, resultHandler.getExitValue());
            }
            return new ImmutablePair<>(localStdoutStream.toString(utf8), localStdErrStream.toString(utf8));
        } catch (InterruptedException | IOException e) {
            throw new RuntimeException("could not run command: " + command, e);
        } finally {
            LOG.info("exit code: " + resultHandler.getExitValue());
            try {
                LOG.info("stderr was: " + localStdErrStream.toString(utf8));
                LOG.info("stdout was: " + localStdoutStream.toString(utf8));
            } catch (UnsupportedEncodingException e) {
                throw new RuntimeException("utf-8 does not exist?", e);
            }
        }
    }

    private Map<String, Map<String, String>> pullFiles(Map<String, Object> cwl, Map<String, Map<String, Object>> inputsOutputs) {
        Map<String, Map<String, String>> fileMap = new HashMap<>();

        LOG.info("DOWNLOADING INPUT FILES...");

        LOG.info(cwl.get("inputs"));

        List<Map<String, Object>> files = (List) cwl.get("inputs");

        // for each file input from the CWL
        for (Map<String, Object> file : files) {

            // pull back the name of the input from the CWL
            LOG.info(file.toString());
            // remove the hash from the cwlInputFileID
            String cwlInputFileID = ((String)file.get("id")).substring(1);
            LOG.info("ID: " + cwlInputFileID);

            // now that I have an input name from the CWL I can find it in the JSON parameterization for this run
            LOG.info("JSON: " + inputsOutputs.toString());
            for (String paramName : inputsOutputs.keySet()) {
                HashMap param = (HashMap)inputsOutputs.get(paramName);
                String path = (String)param.get("path");

                if (paramName.equals(cwlInputFileID)) {

                    // if it's the current one
                    LOG.info("PATH TO DOWNLOAD FROM: " + path + " FOR " + cwlInputFileID + " FOR " + paramName);

                    // output
                    // TODO: poor naming here, need to cleanup the variables
                    // just file name
                    // the file URL
                    File filePathObj = new File(cwlInputFileID);
                    String newDirectory = globalWorkingDir + "/inputs/" + UUID.randomUUID().toString();
                    executeCommand("mkdir -p " + newDirectory);
                    File newDirectoryFile = new File(newDirectory);
                    String uuidPath = newDirectoryFile.getAbsolutePath() + "/" + filePathObj.getName();

                    // VFS call, see https://github.com/abashev/vfs-s3/tree/branch-2.3.x and
                    // https://commons.apache.org/proper/commons-vfs/filesystems.html
                    FileSystemManager fsManager;
                    try {

                        // trigger a copy from the URL to a local file path that's a UUID to avoid collision
                        fsManager = VFS.getManager();
                        FileObject src = fsManager.resolveFile(path);
                        FileObject dest = fsManager.resolveFile(new File(uuidPath).getAbsolutePath());
                        dest.copyFrom(src, Selectors.SELECT_SELF);

                        // now add this info to a hash so I can later reconstruct a docker -v command
                        Map<String, String> new1 = new HashMap<>();
                        new1.put("local_path", uuidPath);
                        new1.put("docker_path", cwlInputFileID);
                        new1.put("url", path);
                        fileMap.put(cwlInputFileID, new1);

                    } catch (FileSystemException e) {
                        LOG.error(e.getMessage());
                        throw new RuntimeException("Could not provision input files", e);
                    }

                    LOG.info("DOWNLOADED FILE: LOCAL: " + cwlInputFileID + " URL: " + path);

                }
            }

        }
        return fileMap;
    }


    private Map<String, Object> parseCWL(String cwlFile, boolean validate) {
        try {
            // update seems to just output the JSON version without checking file links
            String[] s = new String[]{"cwltool", validate?"--print-pre":"--update", cwlFile };
            final ImmutablePair<String, String> execute = this.executeCommand(Joiner.on(" ").join(Arrays.asList(s)));
            Map<String, Object> obj = (Map<String, Object>)yaml.load(execute.getLeft());
            return obj;
        } catch (ComposerException e){
            throw new RuntimeException("Must be single object at root", e);
        }
    }

    private CommandLine parseCommandLine(CommandLineParser parser, String[] args) {
        try {
            // parse the command line arguments
            Options options = new Options();
            options.addOption("c", "config", true, "the INI config file for this tool");
            options.addOption("d", "descriptor", true, "a CWL tool descriptor used to construct the command and run it");
            options.addOption("j", "job", true, "a JSON parameterization of the CWL tool, includes URLs for inputs and outputs");
            return parser.parse(options, args);
        } catch (ParseException exp) {
            LOG.error("Unexpected exception:" + exp.getMessage());
            throw new RuntimeException("Could not parse command-line", exp);
        }
    }


    public static void main(String[] args) {
        final LauncherCWL launcherCWL = new LauncherCWL(args);
        launcherCWL.run();
    }

}
