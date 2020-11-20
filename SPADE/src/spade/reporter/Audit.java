/*
 --------------------------------------------------------------------------------
 SPADE - Support for Provenance Auditing in Distributed Environments.
 Copyright (C) 2015 SRI International

 This program is free software: you can redistribute it and/or
 modify it under the terms of the GNU General Public License as
 published by the Free Software Foundation, either version 3 of the
 License, or (at your option) any later version.

 This program is distributed in the hope that it will be useful,
 but WITHOUT ANY WARRANTY; without even the implied warranty of
 MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the GNU
 General Public License for more details.

 You should have received a copy of the GNU General Public License
 along with this program. If not, see <http://www.gnu.org/licenses/>.
 --------------------------------------------------------------------------------
 */
package spade.reporter;

import java.io.BufferedReader;
import java.io.File;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.PrintWriter;
import java.math.BigInteger;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.logging.Level;
import java.util.logging.Logger;

import org.apache.commons.codec.digest.DigestUtils;
import org.apache.commons.io.FileUtils;

import spade.core.AbstractEdge;
import spade.core.AbstractReporter;
import spade.core.AbstractVertex;
import spade.core.Settings;
import spade.edge.opm.Used;
import spade.edge.opm.WasDerivedFrom;
import spade.edge.opm.WasGeneratedBy;
import spade.edge.opm.WasTriggeredBy;
import spade.reporter.audit.AuditEventReader;
import spade.reporter.audit.Globals;
import spade.reporter.audit.IPCManager;
import spade.reporter.audit.LinuxPathResolver;
import spade.reporter.audit.MalformedAuditDataException;
import spade.reporter.audit.NetfilterHooksManager;
import spade.reporter.audit.OPMConstants;
import spade.reporter.audit.PathRecord;
import spade.reporter.audit.SYSCALL;
import spade.reporter.audit.artifact.ArtifactIdentifier;
import spade.reporter.audit.artifact.ArtifactManager;
import spade.reporter.audit.artifact.DirectoryIdentifier;
import spade.reporter.audit.artifact.MemoryIdentifier;
import spade.reporter.audit.artifact.NetworkSocketIdentifier;
import spade.reporter.audit.artifact.PathIdentifier;
import spade.reporter.audit.artifact.PosixMessageQueue;
import spade.reporter.audit.artifact.UnixSocketIdentifier;
import spade.reporter.audit.artifact.UnknownIdentifier;
import spade.reporter.audit.artifact.UnnamedNetworkSocketPairIdentifier;
import spade.reporter.audit.artifact.UnnamedPipeIdentifier;
import spade.reporter.audit.artifact.UnnamedUnixSocketPairIdentifier;
import spade.reporter.audit.process.FileDescriptor;
import spade.reporter.audit.process.ProcessManager;
import spade.reporter.audit.process.ProcessWithAgentManager;
import spade.reporter.audit.process.ProcessWithoutAgentManager;
import spade.utility.Execute;
import spade.utility.Execute.Output;
import spade.utility.FileUtility;
import spade.utility.HelperFunctions;
import spade.vertex.opm.Artifact;
import spade.vertex.opm.Process;

/**
 * @author Dawood Tariq, Sharjeel Ahmed Qureshi
 */
public class Audit extends AbstractReporter {

	static final Logger logger = Logger.getLogger(Audit.class.getName());

	/********************** LINUX CONSTANTS - START *************************/
	
	

	//  Following constant values are taken from:
	//  http://lxr.free-electrons.com/source/include/uapi/asm-generic/fcntl.h#L19
	private final int O_RDONLY = 00000000, O_WRONLY = 00000001, O_RDWR = 00000002, 
			O_CREAT = 00000100, O_TRUNC = 00001000, O_APPEND = 00002000;
	
	//  Following constant values are taken from:
	//  http://lxr.free-electrons.com/source/include/uapi/asm-generic/mman-common.h#L21
	private final int MAP_ANONYMOUS = 0x20;
	
	//  Following constant values are taken from:
	//  http://lxr.free-electrons.com/source/include/uapi/asm-generic/fcntl.h#L99
	private final int F_LINUX_SPECIFIC_BASE = 1024, F_DUPFD = 0, F_SETFL = 4;
	//  Following constant values are taken from:
	//  http://lxr.free-electrons.com/source/include/uapi/linux/fcntl.h#L16
	private final int F_DUPFD_CLOEXEC = F_LINUX_SPECIFIC_BASE + 6;
	// Source of following: http://elixir.free-electrons.com/linux/latest/source/include/uapi/asm-generic/errno.h#L97
	private final int EINPROGRESS = -115;
	// Source: http://elixir.free-electrons.com/linux/latest/source/include/linux/net.h#L65
	private final int SOCK_STREAM = 1, SOCK_DGRAM = 2, SOCK_SEQPACKET = 5;
	// Source: https://elixir.bootlin.com/linux/latest/source/include/linux/socket.h#L162
	private final int AF_UNIX = 1, AF_LOCAL = 1, AF_INET = 2, AF_INET6 = 10;
	private final int PF_UNIX = AF_UNIX, PF_LOCAL = AF_LOCAL, PF_INET = AF_INET, PF_INET6 = AF_INET6;
	

//	https://elixir.bootlin.com/linux/latest/source/include/uapi/linux/ptrace.h
	private static final int PTRACE_POKETEXT = 4, PTRACE_POKEDATA = 5, PTRACE_POKEUSER = 6,
//			https://elixir.bootlin.com/linux/latest/source/arch/ia64/include/uapi/asm/ptrace.h#L244
			PTRACE_SETREGS = 13, 
//			https://elixir.bootlin.com/linux/latest/source/arch/mips/include/uapi/asm/ptrace.h#L54
			PTRACE_SETFPREGS = 15,
			PTRACE_SETREGSET = 16901, PTRACE_SETSIGINFO = 16899, PTRACE_SETSIGMASK = 16907, 
//			https://elixir.bootlin.com/linux/latest/source/arch/mips/include/uapi/asm/ptrace.h#L61
			PTRACE_SET_THREAD_AREA = 26,
			// Non-data flow constants below
			PTRACE_SETOPTIONS = 16896, PTRACE_CONT = 7, PTRACE_SYSCALL = 24, PTRACE_SINGLESTEP = 9,
//			https://elixir.bootlin.com/linux/latest/source/arch/x86/include/uapi/asm/ptrace-abi.h
			PTRACE_SYSEMU = 31, PTRACE_SYSEMU_SINGLESTEP = 32,
			PTRACE_LISTEN = 16904, PTRACE_KILL = 8, PTRACE_INTERRUPT = 16903, PTRACE_ATTACH = 16, PTRACE_DETACH = 17;
	
	private static final Map<Integer, String> ptraceActions = new HashMap<Integer, String>();
	
	static{
		ptraceActions.put(PTRACE_POKETEXT, "PTRACE_POKETEXT");
		ptraceActions.put(PTRACE_POKEDATA, "PTRACE_POKEDATA");
		ptraceActions.put(PTRACE_POKEUSER, "PTRACE_POKEUSER");
		ptraceActions.put(PTRACE_SETREGS, "PTRACE_SETREGS");
		ptraceActions.put(PTRACE_SETFPREGS, "PTRACE_SETFPREGS");
		ptraceActions.put(PTRACE_SETREGSET, "PTRACE_SETREGSET");
		ptraceActions.put(PTRACE_SETSIGINFO, "PTRACE_SETSIGINFO");
		ptraceActions.put(PTRACE_SETSIGMASK, "PTRACE_SETSIGMASK");
		ptraceActions.put(PTRACE_SET_THREAD_AREA, "PTRACE_SET_THREAD_AREA");
		ptraceActions.put(PTRACE_SETOPTIONS, "PTRACE_SETOPTIONS");
		ptraceActions.put(PTRACE_CONT, "PTRACE_CONT");
		ptraceActions.put(PTRACE_SYSCALL, "PTRACE_SYSCALL");
		ptraceActions.put(PTRACE_SINGLESTEP, "PTRACE_SINGLESTEP");
		ptraceActions.put(PTRACE_SYSEMU, "PTRACE_SYSEMU");
		ptraceActions.put(PTRACE_SYSEMU_SINGLESTEP, "PTRACE_SYSEMU_SINGLESTEP");
		ptraceActions.put(PTRACE_LISTEN, "PTRACE_LISTEN");
		ptraceActions.put(PTRACE_KILL, "PTRACE_KILL");
		ptraceActions.put(PTRACE_INTERRUPT, "PTRACE_INTERRUPT");
		ptraceActions.put(PTRACE_ATTACH, "PTRACE_ATTACH");
		ptraceActions.put(PTRACE_DETACH, "PTRACE_DETACH");
	}
	
//	Source: https://elixir.bootlin.com/linux/v4.15.18/source/include/uapi/asm-generic/mman-common.h#L39
	private static final Map<Integer, String> madviseAdviceValues = new HashMap<Integer, String>();
	static{
		madviseAdviceValues.put(0, 	"MADV_NORMAL");
		madviseAdviceValues.put(1, 	"MADV_RANDOM");
		madviseAdviceValues.put(2, 	"MADV_SEQUENTIAL");
		madviseAdviceValues.put(3, 	"MADV_WILLNEED");
		madviseAdviceValues.put(4, 	"MADV_DONTNEED");
		madviseAdviceValues.put(8, 	"MADV_FREE");
		madviseAdviceValues.put(9, 	"MADV_REMOVE");
		madviseAdviceValues.put(10, "MADV_DONTFORK");
		madviseAdviceValues.put(11, "MADV_DOFORK");
		madviseAdviceValues.put(12, "MADV_MERGEABLE");
		madviseAdviceValues.put(13, "MADV_UNMERGEABLE");
		madviseAdviceValues.put(14, "MADV_HUGEPAGE");
		madviseAdviceValues.put(15, "MADV_NOHUGEPAGE");
		madviseAdviceValues.put(16, "MADV_DONTDUMP");
		madviseAdviceValues.put(17, "MADV_DODUMP");
		madviseAdviceValues.put(18, "MADV_WIPEONFORK");
		madviseAdviceValues.put(19, "MADV_KEEPONFORK");
		madviseAdviceValues.put(100,"MADV_HWPOISON");
		madviseAdviceValues.put(101,"MADV_SOFT_OFFLINE");
	}
	
//	Source: https://elixir.bootlin.com/linux/v4.15.18/source/include/uapi/linux/fs.h#L35
	private static final Map<Integer, String> lseekWhenceValues = new HashMap<Integer, String>();
	static{
		lseekWhenceValues.put(0, "SEEK_SET");
		lseekWhenceValues.put(1, "SEEK_CUR");
		lseekWhenceValues.put(2, "SEEK_END");
		lseekWhenceValues.put(3, "SEEK_DATA");
		lseekWhenceValues.put(4, "SEEK_HOLE");
	}
	/********************** LINUX CONSTANTS - END *************************/

	/********************** PROCESS STATE - START *************************/
	
	private ProcessManager processManager;

	/********************** PROCESS STATE - END *************************/
	
	/********************** ARITFACT STATE - START *************************/
	
	private ArtifactManager artifactManager;
	
	/********************** ARTIFACT STATE - END *************************/
	
	/********************** NETFILTER HOOKS STATE - START *************************/
	
	private NetfilterHooksManager netfilterHooksManager;
	
	/********************** NETFILTER HOOKS STATE - END *************************/
	
	private IPCManager ipcManager = new IPCManager(this);
	
	/********************** NETFILTER - START *************************/
	
	private String[] iptablesRules = null;
	
//	private int matchedNetfilterSyscall = 0,
//			matchedSyscallNetfilter = 0;
//	
//	private List<Map<String, String>> networkAnnotationsFromSyscalls = 
//			new ArrayList<Map<String, String>>();
//	private List<Map<String, String>> networkAnnotationsFromNetfilter = 
//			new ArrayList<Map<String, String>>();
//	
//	private Map<String, String> getNetworkAnnotationsSeenInList(
//			List<Map<String, String>> list, String remoteAddress, String remotePort){
//		for(int a = 0; a < list.size(); a++){
//			Map<String, String> artifactAnnotation = list.get(a);
//			if(String.valueOf(artifactAnnotation.get(OPMConstants.ARTIFACT_REMOTE_ADDRESS)).equals(remoteAddress) &&
//					String.valueOf(artifactAnnotation.get(OPMConstants.ARTIFACT_REMOTE_PORT)).equals(remotePort)){
//				return artifactAnnotation;
//			}
//		}
//		return null;
//	}
	
	/********************** NETFILTER - END *************************/

	/********************** BEHAVIOR FLAGS - START *************************/
	
	private Globals globals = null;
	//Reporting variables
	private boolean reportingEnabled = false;
	private long reportEveryMs;
	private long lastReportedTime;
	
	// These are the default values
	private boolean FAIL_FAST = true;
	private boolean USE_READ_WRITE = false;
	private boolean USE_SOCK_SEND_RCV = false;
	private boolean CREATE_BEEP_UNITS = false;
	private boolean SIMPLIFY = true;
	private boolean PROCFS = false;
	private boolean WAIT_FOR_LOG_END = true;
	private boolean AGENTS = false;
	private boolean CONTROL = true;
	private boolean USE_MEMORY_SYSCALLS = true;
	private String AUDITCTL_SYSCALL_SUCCESS_FLAG = "1";
	private boolean ANONYMOUS_MMAP = true;
	private String ADD_KM_KEY = "localEndpoints";
	private boolean ADD_KM; // Default value set where flags are being initialized from arguments (unlike the variables above).
	private String HANDLE_KM_RECORDS_KEY = "handleLocalEndpoints";
	// Handle the flag below with care!
	private Boolean HANDLE_KM_RECORDS = null; // Default value set where flags are being initialized from arguments (unlike the variables above).
	private Integer mergeUnit = null;
	private String HARDEN_KEY = "harden";
	private boolean HARDEN = false;
	private String REPORT_KILL_KEY = "reportKill";
	private boolean REPORT_KILL = true;
	private final String HANDLE_CHDIR_KEY = "cwd";
	private boolean HANDLE_CHDIR = true;
	private final String HANDLE_ROOTFS_KEY = "rootFS";
	private boolean HANDLE_ROOTFS = true;
	private final String HANDLE_NAMESPACES_KEY = "namespaces";
	private boolean HANDLE_NAMESPACES = false;
	private final String NETFILTER_HOOKS_KEY = "netfilterHooks";
	private boolean NETFILTER_HOOKS = false;
	private final String HANDLE_NETFILTER_HOOKS_KEY = "handleNetfilterHooks";
	private boolean HANDLE_NETFILTER_HOOKS = false;
	private final String NETFILTER_HOOKS_LOG_CT_KEY = "netfilterHooksLogCT";
	private boolean NETFILTER_HOOKS_LOG_CT = false;
	private final String NETFILTER_HOOKS_USER_KEY = "netfilterHooksUser";
	private boolean NETFILTER_HOOKS_USER = false;
	private final String CAPTURE_IPC_KEY = "logIpc";
	private boolean CAPTURE_IPC = false;
	private final String REPORT_IPC_KEY = "reportIpc";
	private boolean REPORT_IPC = false;

	private String deleteModuleBinaryPath = null;
	/********************** BEHAVIOR FLAGS - END *************************/
	
	/*
	 * Must be less than 40 for now! TODO
	 */
	private String kernelModuleKey = null;
	
	private Set<String> namesOfProcessesToIgnoreFromConfig = new HashSet<String>();
	
	private String spadeAuditBridgeProcessPid = null;
	// true if live audit, false if log file. null not set.
	private Boolean isLiveAudit = null;
	// a flag to block on shutdown call if buffers are being emptied and events are still being read
	private volatile boolean eventReaderThreadRunning = false;
	
	private final long PID_MSG_WAIT_TIMEOUT = 1 * 1000;
	
	private final String AUDIT_SYSCALL_SOURCE = OPMConstants.SOURCE_AUDIT_SYSCALL;
	
	private final String kernelModuleDirectoryPath = "lib/kernel-modules";
	private final String kernelModulePath = kernelModuleDirectoryPath + "/netio.ko";
	private final String kernelModuleControllerPath = kernelModuleDirectoryPath + "/netio_controller.ko";
	
	public static final String PROTOCOL_NAME_UDP = "udp",
			PROTOCOL_NAME_TCP = "tcp";
	private final String IPV4_NETWORK_SOCKET_SADDR_PREFIX = "02";
	private final String IPV6_NETWORK_SOCKET_SADDR_PREFIX = "0A";
	private final String UNIX_SOCKET_SADDR_PREFIX = "01";
	private final String NETLINK_SOCKET_SADDR_PREFIX = "10";
	
	private boolean isNetlinkSaddr(String saddr){
		return saddr != null && saddr.startsWith(NETLINK_SOCKET_SADDR_PREFIX);
	}
	private boolean isUnixSaddr(String saddr){
		return saddr != null && saddr.startsWith(UNIX_SOCKET_SADDR_PREFIX);
	}
	private boolean isNetworkSaddr(String saddr){
		return saddr != null && (isIPv4Saddr(saddr) || isIPv6Saddr(saddr));
	}
	private boolean isIPv4Saddr(String saddr){
		return saddr != null && saddr.startsWith(IPV4_NETWORK_SOCKET_SADDR_PREFIX);
	}
	private boolean isIPv6Saddr(String saddr){
		return saddr != null && saddr.startsWith(IPV6_NETWORK_SOCKET_SADDR_PREFIX);
	}
	
	/**
	 * Returns a map which contains all the keys and values defined 
	 * in the default config file. 
	 * 
	 * Returns empty map if failed to read the config file.
	 * 
	 * @return HashMap<String, String>
	 */
	private Map<String, String> readDefaultConfigMap(){
		Map<String, String> configMap = new HashMap<String, String>();
		try{
			Map<String, String> temp = FileUtility.readConfigFileAsKeyValueMap(
					Settings.getDefaultConfigFilePath(this.getClass()),
					"=");
			if(temp != null){
				configMap.putAll(temp);
			}
		}catch(Exception e){
			logger.log(Level.WARNING, "Failed to read config", e);
		}
		return configMap;
	}
	
	/**
	 * Initializes the reporting globals based on the argument
	 * 
	 * The argument is read from the config file
	 * 
	 * Returns true if the value in the config file was defined properly or not 
	 * defined. If the value in the config value is ill-defined then returns false.
	 * 
	 * @param reportingIntervalSeconds Interval time in seconds to report stats after
	 */
	private boolean initReporting(String reportingIntervalSeconds){
		Long reportingInterval = HelperFunctions.parseLong(reportingIntervalSeconds, null);
		if(reportingInterval != null){
			if(reportingInterval < 1){ //at least 1 ms
				logger.log(Level.INFO, "Statistics reporting turned off");
			}else{
				reportingEnabled = true;
				reportEveryMs = reportingInterval * 1000;
				lastReportedTime = System.currentTimeMillis();
			}
		}else if(reportingInterval == null && 
				(reportingIntervalSeconds != null && !reportingIntervalSeconds.isEmpty())){
			logger.log(Level.SEVERE, "Invalid value for reporting interval in the config file");
			return false;
		}
		return true;
	}
	
	/**
	 * Returns true if the argument is null, true, false, 1, 0, yes or no.
	 * Else returns false.
	 * 
	 * @param str value to check
	 * @return true/false
	 */
	private boolean isValidBoolean(String str){
		return str == null 
				|| "true".equalsIgnoreCase(str.trim()) || "false".equalsIgnoreCase(str.trim())
				|| "1".equals(str.trim()) || "0".equals(str.trim())
				|| "yes".equalsIgnoreCase(str.trim()) || "no".equals(str.trim())
				|| "on".equalsIgnoreCase(str.trim()) || "off".equals(str.trim());

	}
	
	/**
	 * If the argument is null or doesn't match any of the value boolean options:
	 * true, false, 1, 0, yes or no then default value is returned. Else the parsed
	 * value is returned.
	 * 
	 * @param str string to convert to boolean
	 * @param defaultValue default value
	 * @return true/false
	 */
	private boolean parseBoolean(String str, boolean defaultValue){
		if(str == null){
			return defaultValue;
		}else{
			str = str.trim();
			if(str.equals("1") || str.equalsIgnoreCase("yes") || str.equalsIgnoreCase("true") || str.equalsIgnoreCase("on")){
				return true;
			}else if(str.equals("0") || str.equalsIgnoreCase("no") || str.equalsIgnoreCase("false") || str.equalsIgnoreCase("off")){
				return false;
			}else{
				return defaultValue;
			}
		}
	}
	
	public final boolean getFlagControl(){
		return CONTROL;
	}
	
	/**
	 * Initializes global boolean flags for this reporter
	 * 
	 * @param args a map made from arguments key-values
	 * @return true if all flags had valid values / false if any of the flags had a non-boolean value
	 */
	private boolean initFlagsFromArguments(Map<String, String> args){
		try{
			globals = Globals.parseArguments(args);
			if(globals == null){
				throw new Exception("NULL globals object. Failed to initialize flags.");
			}
		}catch(Exception e){
			logger.log(Level.SEVERE, "Failed to parse arguments", e);
			return false;
		}
		
		String argValue = args.get("failfast");
		if(isValidBoolean(argValue)){
			FAIL_FAST = parseBoolean(argValue, FAIL_FAST);
		}else{
			logger.log(Level.SEVERE, "Invalid flag value for 'failfast': " + argValue);
			return false;
		}
		
		argValue = args.get("fileIO");
		if(isValidBoolean(argValue)){
			USE_READ_WRITE = parseBoolean(argValue, USE_READ_WRITE);
		}else{
			logger.log(Level.SEVERE, "Invalid flag value for 'fileIO': " + argValue);
			return false;
		}
		
		argValue = args.get("netIO");
		if(isValidBoolean(argValue)){
			USE_SOCK_SEND_RCV = parseBoolean(argValue, USE_SOCK_SEND_RCV);
		}else{
			logger.log(Level.SEVERE, "Invalid flag value for 'netIO': " + argValue);
			return false;
		}
		
		argValue = args.get("units");
		if(isValidBoolean(argValue)){
			CREATE_BEEP_UNITS = parseBoolean(argValue, CREATE_BEEP_UNITS);
		}else{
			logger.log(Level.SEVERE, "Invalid flag value for 'units': " + argValue);
			return false;
		}

		argValue = args.get("agents");
		if(isValidBoolean(argValue)){
			AGENTS = parseBoolean(argValue, AGENTS);
		}else{
			logger.log(Level.SEVERE, "Invalid flag value for 'agents': " + argValue);
			return false;
		}

		// Arguments below are only for experimental use
		argValue = args.get("simplify");
		if(isValidBoolean(argValue)){
			SIMPLIFY = parseBoolean(argValue, SIMPLIFY);
		}else{
			logger.log(Level.SEVERE, "Invalid flag value for 'simplify': " + argValue);
			return false;
		}
		
		argValue = args.get("procFS");
		if(isValidBoolean(argValue)){
			PROCFS = parseBoolean(argValue, PROCFS);
			if(PROCFS){
				logger.log(Level.SEVERE, "'procFS' cannot be enabled!");
				return false;
			}
		}else{
			logger.log(Level.SEVERE, "Invalid flag value for 'procFS': " + argValue);
			return false;
		}

		argValue = args.get("waitForLog");
		if(isValidBoolean(argValue)){
			WAIT_FOR_LOG_END = parseBoolean(argValue, WAIT_FOR_LOG_END);
		}else{
			logger.log(Level.SEVERE, "Invalid flag value for 'waitForLog': " + argValue);
			return false;
		}
		
		argValue = args.get("memorySyscalls");
		if(isValidBoolean(argValue)){
			USE_MEMORY_SYSCALLS = parseBoolean(argValue, USE_MEMORY_SYSCALLS);
		}else{
			logger.log(Level.SEVERE, "Invalid flag value for 'memorySyscalls': " + argValue);
			return false;
		}

		argValue = args.get("control");
		if(isValidBoolean(argValue)){
			CONTROL = parseBoolean(argValue, CONTROL);
		}else{
			logger.log(Level.SEVERE, "Invalid flag value for 'control': " + argValue);
			return false;
		}
		
		// Ignore for now. Changing it now would break code in places.
		// Sucess always assumed to be '1' for now (default value)
		// TODO
//		if("0".equals(args.get("auditctlSuccessFlag"))){
//			AUDITCTL_SYSCALL_SUCCESS_FLAG = "0";
//		}
		
		argValue = args.get("anonymousMmap");
		if(isValidBoolean(argValue)){
			ANONYMOUS_MMAP = parseBoolean(argValue, ANONYMOUS_MMAP);
		}else{
			logger.log(Level.SEVERE, "Invalid flag value for 'anonymousMmap': " + argValue);
			return false;
		}
		
		argValue = args.get(HARDEN_KEY);
		if(isValidBoolean(argValue)){
			HARDEN = parseBoolean(argValue, HARDEN);
		}else{
			logger.log(Level.SEVERE, "Invalid flag value for '"+HARDEN_KEY+"': " + argValue);
			return false;
		}
		
		argValue = args.get(REPORT_KILL_KEY);
		if(isValidBoolean(argValue)){
			REPORT_KILL = parseBoolean(argValue, REPORT_KILL);
		}else{
			logger.log(Level.SEVERE, "Invalid flag value for '"+REPORT_KILL_KEY+"': " + argValue);
			return false;
		}
		
		// Setting default values here instead of where variables are defined because default values for KM vars depend
		// on whether the data is live or playback.
		final boolean logPlayback = argsSpecifyLogPlayback(args);
		String addKmArgValue = args.get(ADD_KM_KEY);
		String handleKmArgValue = args.get(HANDLE_KM_RECORDS_KEY);
		if(logPlayback){ // Can't use isLiveAudit flag because not set yet.
			// default values
			ADD_KM = false; // Doesn't matter for log playback so always false.
			if("true".equals(handleKmArgValue)){
				HANDLE_KM_RECORDS = true;
			}else if("false".equals(handleKmArgValue)){
				HANDLE_KM_RECORDS = false;
			}else if(handleKmArgValue == null){
				HANDLE_KM_RECORDS = null; // To be decided by the first network related record
			}else{
				logger.log(Level.SEVERE, "Invalid flag value for '"+HANDLE_KM_RECORDS_KEY+"': " + argValue);
				return false;
			}
		}else{ // live audit
			// Default values
			try{
				ADD_KM = FileUtility.doesPathExist(kernelModuleDirectoryPath) && FileUtility.isDirectory(kernelModuleDirectoryPath);
			}catch(Exception e){
				logger.log(Level.SEVERE, "Failed to check if directory exists: '"+kernelModuleDirectoryPath+"'", e);
				return false;
			}

			boolean addKmUserArgument;
			// Parsing the values for the KM vars after the default values have be set appropriately (see above)
			if(isValidBoolean(addKmArgValue)){
				addKmUserArgument = parseBoolean(addKmArgValue, false);//ADD_KM);
			}else{
				logger.log(Level.SEVERE, "Invalid flag value for '"+ADD_KM_KEY+"': " + addKmArgValue);
				return false;
			}

			if(!ADD_KM && addKmUserArgument){ // kernel module doesn't exist but user is asking to use kernel module
				logger.log(Level.SEVERE, "Kernel module directory '"+kernelModuleDirectoryPath+"' doesn't exist");
				logger.log(Level.SEVERE, "To use '"+ADD_KM_KEY+"=true' rebuild SPADE using the command: 'make KERNEL_MODULES=true'");
				return false;
			}

			ADD_KM = addKmUserArgument;

			// If added modules then also must handle. If not added then cannot handle.
			HANDLE_KM_RECORDS = ADD_KM;
		}
		
		argValue = args.get(HANDLE_NETFILTER_HOOKS_KEY);
		if(isValidBoolean(argValue)){
			HANDLE_NETFILTER_HOOKS = parseBoolean(argValue, HANDLE_NETFILTER_HOOKS);
		}else{
			logger.log(Level.SEVERE, "Invalid flag value for '"+HANDLE_NETFILTER_HOOKS_KEY+"': " + argValue);
			return false;
		}

		argValue = args.get(NETFILTER_HOOKS_KEY);
		if(isValidBoolean(argValue)){
			NETFILTER_HOOKS = parseBoolean(argValue, NETFILTER_HOOKS);
		}else{
			logger.log(Level.SEVERE, "Invalid flag value for '"+NETFILTER_HOOKS_KEY+"': " + argValue);
			return false;
		}

		argValue = args.get(NETFILTER_HOOKS_LOG_CT_KEY);
		if(isValidBoolean(argValue)){
			NETFILTER_HOOKS_LOG_CT = parseBoolean(argValue, NETFILTER_HOOKS_LOG_CT);
		}else{
			logger.log(Level.SEVERE, "Invalid flag value for '"+NETFILTER_HOOKS_LOG_CT_KEY+"': " + argValue);
			return false;
		}

		argValue = args.get(NETFILTER_HOOKS_USER_KEY);
		if(isValidBoolean(argValue)){
			NETFILTER_HOOKS_USER = parseBoolean(argValue, NETFILTER_HOOKS_USER);
		}else{
			logger.log(Level.SEVERE, "Invalid flag value for '"+NETFILTER_HOOKS_USER_KEY+"': " + argValue);
			return false;
		}

		if(!ADD_KM && NETFILTER_HOOKS){
			logger.log(Level.SEVERE, "Argument '"+ADD_KM_KEY+"' must be 'true' for argument '"+NETFILTER_HOOKS_KEY+"' to be 'true'");
			return false;
		}

		String mergeUnitKey = "mergeUnit";
		String mergeUnitValue = args.get(mergeUnitKey);
		if(mergeUnitValue != null){
			mergeUnit = HelperFunctions.parseInt(mergeUnitValue, null);
			if(mergeUnit != null){
				if(mergeUnit < 0){ // must be positive
					mergeUnit = null;
					logger.log(Level.SEVERE, "'"+mergeUnitKey+"' must be non-negative: '" + mergeUnitValue+"'");
					return false;
				}
			}else{
				logger.log(Level.SEVERE, "'"+mergeUnitKey+"' must be an integer: '" + mergeUnitValue+"'");
				return false;
			}
		}
		
		argValue = args.get(HANDLE_CHDIR_KEY);
		if(isValidBoolean(argValue)){
			HANDLE_CHDIR = parseBoolean(argValue, HANDLE_CHDIR);
		}else{
			logger.log(Level.SEVERE, "Invalid flag value for '"+HANDLE_CHDIR_KEY+"': " + argValue);
			return false;
		}
		
		argValue = args.get(HANDLE_ROOTFS_KEY);
		if(isValidBoolean(argValue)){
			HANDLE_ROOTFS = parseBoolean(argValue, HANDLE_ROOTFS);
		}else{
			logger.log(Level.SEVERE, "Invalid flag value for '"+HANDLE_ROOTFS_KEY+"': " + argValue);
			return false;
		}
		
		argValue = args.get(CAPTURE_IPC_KEY);
		if(isValidBoolean(argValue)){
			CAPTURE_IPC = parseBoolean(argValue, CAPTURE_IPC);
		}else{
			logger.log(Level.SEVERE, "Invalid flag value for '"+CAPTURE_IPC_KEY+"': " + argValue);
			return false;
		}
		
		argValue = args.get(REPORT_IPC_KEY);
		if(isValidBoolean(argValue)){
			REPORT_IPC = parseBoolean(argValue, REPORT_IPC);
		}else{
			logger.log(Level.SEVERE, "Invalid flag value for '"+REPORT_IPC_KEY+"': " + argValue);
			return false;
		}

		if(HANDLE_ROOTFS){
			if(!HANDLE_CHDIR){
				logger.log(Level.INFO, "'"+HANDLE_CHDIR_KEY+"' set to 'true' because '"+HANDLE_ROOTFS_KEY+"'='true'");
				HANDLE_CHDIR = true;
			}
		}
		
		argValue = args.get(HANDLE_NAMESPACES_KEY);
		if(isValidBoolean(argValue)){
			HANDLE_NAMESPACES = parseBoolean(argValue, HANDLE_NAMESPACES);
		}else{
			logger.log(Level.SEVERE, "Invalid flag value for '"+HANDLE_NAMESPACES_KEY+"': " + argValue);
			return false;
		}
//		
		if(ADD_KM && (HANDLE_KM_RECORDS != null && !HANDLE_KM_RECORDS)){
			logger.log(Level.SEVERE, "Must handle kernel module data if kernel module added.");
			return false;
		}else{
			// Logging only relevant flags now for debugging
			logger.log(Level.INFO, "Audit flags: {0}={1}, {2}={3}, {4}={5}, {6}={7}, {8}={9}, {10}={11}, {12}={13}, "
                       + "{14}={15}, {16}={17}, {18}={19}, {20}={21}, {22}={23}, {24}={25}, {26}={27}, {28}={29}, "
                       + "{30}={31}, {32}={33}, {34}={35}, {36}={37}, {38}={39}, {40}={41}, {42}={43}",
					new Object[]{"syscall", args.get("syscall"), "fileIO", USE_READ_WRITE, "netIO", USE_SOCK_SEND_RCV, 
							"units", CREATE_BEEP_UNITS, "waitForLog", WAIT_FOR_LOG_END, "netfilter", false, 
							"refineNet", false, ADD_KM_KEY, ADD_KM, 
							HANDLE_KM_RECORDS_KEY, HANDLE_KM_RECORDS, "failfast", FAIL_FAST,
							mergeUnitKey, mergeUnit, HARDEN_KEY, HARDEN, REPORT_KILL_KEY, REPORT_KILL,
							HANDLE_CHDIR_KEY, HANDLE_CHDIR,
							HANDLE_ROOTFS_KEY, HANDLE_ROOTFS,
							HANDLE_NAMESPACES_KEY, HANDLE_NAMESPACES,
							HANDLE_NETFILTER_HOOKS_KEY, HANDLE_NETFILTER_HOOKS,
							NETFILTER_HOOKS_KEY, NETFILTER_HOOKS,
							NETFILTER_HOOKS_LOG_CT_KEY, NETFILTER_HOOKS_LOG_CT,
							NETFILTER_HOOKS_USER_KEY, NETFILTER_HOOKS_USER,
							CAPTURE_IPC_KEY, CAPTURE_IPC,
							REPORT_IPC_KEY, REPORT_IPC});
			logger.log(Level.INFO, globals.toString());
			return true;
		}
	}
	
	/**
	 * Creates a temp file in the given tempDir from the list of audit log files
	 * 
	 * Returns the path of the temp file is that is created successfully
	 * Else returns null
	 * 
	 * @param spadeAuditBridgeBinaryName name of the spade audit bridge binary
	 * @param inputLogFiles list of audit log files (in the defined order)
	 * @param tempDirPath path of the temp dir
	 * @return path of the temp file which contains the paths of audit logs OR null
	 */
	private String createLogListFileForSpadeAuditBridge(String spadeAuditBridgeBinaryName, 
			List<String> inputLogFiles, String tempDirPath){
		try{
			String spadeAuditBridgeInputFilePath = tempDirPath + File.separatorChar + 
					spadeAuditBridgeBinaryName + "." + System.nanoTime();
			File spadeAuditBridgeInputFile = new File(spadeAuditBridgeInputFilePath);
			if(!spadeAuditBridgeInputFile.createNewFile()){
				logger.log(Level.SEVERE, "Failed to create input file list file for " + spadeAuditBridgeBinaryName);
				return null;
			}else{
				FileUtils.writeLines(spadeAuditBridgeInputFile, inputLogFiles);
				return spadeAuditBridgeInputFile.getAbsolutePath();
			}
		}catch(Exception e){
			logger.log(Level.SEVERE, "Failed to create input file for " + spadeAuditBridgeBinaryName, e);
		}
		return null;
	}
	
	/**
	 * Returns a list of audit log files if the rotate flag is true.
	 * Else returns a list with only the given audit log file as it's element.
	 * 
	 * The audit log files are added in the convention defined in the function code.
	 * 
	 * @param inputAuditLogFilePath path of the audit log file
	 * @param rotate a flag to tell whether to read the rotated logs or not
	 * @return list if input log files or null if error
	 */
	private List<String> getListOfInputAuditLogs(String inputAuditLogFilePath, boolean rotate){
		// Build a list of audit log files to be read
		LinkedList<String> inputAuditLogFiles = new LinkedList<String>();
		inputAuditLogFiles.addFirst(inputAuditLogFilePath); //add the file in the argument
		if(rotate){ //if rotate is true then add the rest too based on the decided convention
			//convention: name format of files to be processed -> name.1, name.2 and so on where 
			//name is the name of the file passed in as argument
			//can only process 99 logs
			for(int logCount = 1; logCount<=99; logCount++){
				String logPath = inputAuditLogFilePath + "." + logCount;
				try{
					if(FileUtility.doesPathExist(logPath)){
						if(FileUtility.isFile(logPath)){
							if(FileUtility.isFileReadable(logPath)){
								inputAuditLogFiles.addFirst(logPath); 
								//adding first so that they are added in the reverse order
							}else{
								logger.log(Level.WARNING, "Log skipped because file not readable: " + logPath);
							}
						}
					}
				}catch(Exception e){
					logger.log(Level.SEVERE, "Failed to check if log path is readable: " + logPath, e);
					return null;
				}
			}
		}
		return inputAuditLogFiles;
	}
	
	private void doCleanup(String rulesType, String logListFile){
		if(isLiveAudit){
			if(!"none".equals(rulesType)){
				removeAuditctlRules();
			}
		}else{
			try{
				if(FileUtility.doesPathExist(logListFile)){
					if(FileUtility.isFile(logListFile)){
						if(!FileUtility.deleteFile(logListFile)){
							logger.log(Level.WARNING, "Failed to delete temp log list file: " + logListFile);
						}
					}else{
						logger.log(Level.WARNING, "Failed to delete log list temp file. Not a file: " + logListFile);
					}
				}
			}catch(Exception e){
				logger.log(Level.SEVERE, "Failed to check and delete log list file: " + logListFile, e);
			}
		}
		if(artifactManager != null){
			artifactManager.doCleanUp();
		}
		if(processManager != null){
			processManager.doCleanUp();
		}
		if(netfilterHooksManager != null){
			netfilterHooksManager.shutdown();
		}
	}
	
	private boolean argsSpecifyLogPlayback(Map<String, String> args){
		return args.containsKey("inputDir") || args.containsKey("inputLog");
	}
	
	@Override
	public boolean launch(String arguments) {
		String spadeAuditBridgeBinaryName = null;
		String spadeAuditBridgeBinaryPath = null;
		String outputLogFilePath = null;
		long recordsToRotateOutputLogAfter = 0;
		String spadeAuditBridgeCommand = null;
		String rulesType = null;
		String logListFile = null;
		
		Map<String, String> argsMap = HelperFunctions.parseKeyValPairs(arguments);
		Map<String, String> configMap = readDefaultConfigMap();
		
		// Init reporting globals
		if(!initReporting(configMap.get("reportingIntervalSeconds"))){
			return false;
		}

		// Get path of spadeAuditBridge binary from the config file
		spadeAuditBridgeBinaryPath = configMap.get("spadeAuditBridge");
		try{
			if(!FileUtility.isFileReadable(spadeAuditBridgeBinaryPath)){
				logger.log(Level.SEVERE, "File specified in config by 'spadeAuditBridge' key is not readable: " +
						spadeAuditBridgeBinaryPath);
				return false;
			}
		}catch(Exception e){
			logger.log(Level.SEVERE, "Failed to check if file specified in config by 'spadeAuditBridge' key is readable: " +
					spadeAuditBridgeBinaryPath, e);
			return false;
		}
		
		String spadeAuditBridgeBinaryPathArray[] = spadeAuditBridgeBinaryPath.split(File.separator);
		spadeAuditBridgeBinaryName = spadeAuditBridgeBinaryPathArray[spadeAuditBridgeBinaryPathArray.length - 1];
		
		// Init boolean flags from the arguments
		if(!initFlagsFromArguments(argsMap)){
			return false;
		}
		
		if(HARDEN){
			deleteModuleBinaryPath = configMap.get("deleteModule");
			try{
				if(!FileUtility.isFileReadable(deleteModuleBinaryPath)){
					logger.log(Level.SEVERE, "File specified in config by 'deleteModule' key is not readable: " +
							deleteModuleBinaryPath);
					return false;
				}
			}catch(Exception e){
				logger.log(Level.SEVERE, "Failed to check if file specified in config by 'deleteModule' key is readable: " +
						deleteModuleBinaryPath, e);
				return false;
			}
		}
		
		// Check if the outputLog argument is valid or not
		outputLogFilePath = argsMap.get("outputLog");
		if(outputLogFilePath != null){
			try{
				if(!FileUtility.createFile(outputLogFilePath)){
					logger.log(Level.SEVERE, "Failed to create file specified by 'outputLog' argument: " + outputLogFilePath);
					return false;
				}
			}catch(Exception e){
				logger.log(Level.SEVERE, "Failed to create file specified by 'outputLog' argument: " + outputLogFilePath, e);
				return false;
			}
			String recordsToRotateOutputLogAfterArgument = argsMap.get("outputLogRotate");
			if(recordsToRotateOutputLogAfterArgument != null){
				Long parsedOutputLogRotate = HelperFunctions.parseLong(recordsToRotateOutputLogAfterArgument, null);
				if(parsedOutputLogRotate == null){
					logger.log(Level.SEVERE, "Invalid value for 'outputLogRotate': "+ recordsToRotateOutputLogAfterArgument);
					return false;
				}else{
					recordsToRotateOutputLogAfter = parsedOutputLogRotate;
				}
			}
		}

		String inputLogDirectoryArgument = argsMap.get("inputDir");
		String inputAuditLogFileArgument = argsMap.get("inputLog");
		if(argsSpecifyLogPlayback(argsMap)){
			// is log playback
			isLiveAudit = false;
			
			if(inputAuditLogFileArgument != null){
			
				try{
					if(!FileUtility.isFileReadable(inputAuditLogFileArgument)){
						logger.log(Level.SEVERE, "File specified for 'inputLog' argument not readable: " + inputAuditLogFileArgument);
						return false;
					}
				}catch(Exception e){
					logger.log(Level.SEVERE, "Failed to check if file specified for 'inputLog' argument is readable: "
							+ inputAuditLogFileArgument, e);
					return false;
				}
	
				// Whether to read from rotated logs or not
				boolean rotate = false;
				String rotateArgument = argsMap.get("rotate");
				if(isValidBoolean(rotateArgument)){
					rotate = parseBoolean(rotateArgument, false);
				}else{
					logger.log(Level.SEVERE, "Invalid value for 'rotate' flag: "+ rotateArgument);
					return false;
				}
	
				List<String> inputAuditLogFiles = getListOfInputAuditLogs(inputAuditLogFileArgument, rotate);
				
				if(inputAuditLogFiles == null){
					logger.log(Level.SEVERE, "Failed to get list of input log");
					return false;
				}else{
					logger.log(Level.INFO, "Total logs to process: " + inputAuditLogFiles.size() + " and list = " + inputAuditLogFiles);
				}
	
				// Only needed in case of audit log files and not in case of live audit
				String tempDirPath = configMap.get("tempDir");
				try{
					if(!FileUtility.createDirectories(tempDirPath)){
						logger.log(Level.SEVERE, "Failed to create temp directory defined in config with key 'tempDir': "
								+ tempDirPath);
						return false;
					}
				}catch(Exception e){
					logger.log(Level.SEVERE, "Failed to create temp directory defined in config with key 'tempDir': "
							+ tempDirPath, e);
					return false;
				}
				
				// Create the input file for spadeAuditBridge to read the audit logs from 
				logListFile = createLogListFileForSpadeAuditBridge(spadeAuditBridgeBinaryName, inputAuditLogFiles, tempDirPath);
				if(logListFile == null){
					return false;
				}
				
				// Build the command to use
				spadeAuditBridgeCommand = spadeAuditBridgeBinaryPath + 
								((CREATE_BEEP_UNITS) ? " -u" : "") + 
								((WAIT_FOR_LOG_END) ? " -w" : "") + 
								" -f " + logListFile;
			}else{
				// Input log directory section
				
				try{
					File dir = new File(inputLogDirectoryArgument);
					
					if(dir.exists() && dir.isDirectory()){
						
						// Check if logs exist
						if(dir.list().length != 0){
							
							// Confirm timestamp
							String inputLogTimeArgument = argsMap.get("inputTime");
							if(inputLogTimeArgument != null){
								try{
									SimpleDateFormat dateFormat = new SimpleDateFormat("yyyy-MM-dd:HH:mm:ss");
									dateFormat.parse(inputLogTimeArgument);
									// parsed successfully
								}catch(Exception e){
									logger.log(Level.SEVERE, "Invalid time format for argument 'inputTime'. "
											+ "Expected: yyyy-MM-dd:HH:mm:ss" , e);
									return false;
								}
							}
							
							// Build the command to use
							spadeAuditBridgeCommand = spadeAuditBridgeBinaryPath + 
											((CREATE_BEEP_UNITS) ? " -u" : "") + 
											((WAIT_FOR_LOG_END) ? " -w" : "") + 
											" -d " + inputLogDirectoryArgument +
											((mergeUnit != null) ? " -m " + mergeUnit : "") +
											((inputLogTimeArgument != null) ? " -t " + inputLogTimeArgument : "");
							
						}else{
							logger.log(Level.SEVERE, "No log file in 'inputDir' to process");
							return false;
						}
						
					}else{
						logger.log(Level.SEVERE, "Path for 'inputDir' doesn't exist or isn't a directory");
						return false;
					}
					
				}catch(Exception e){
					logger.log(Level.SEVERE, "Failed to process 'inputDir' argument", e);
					return false;
				}
				
			}

		}else{ // live audit

			isLiveAudit = true;
			
			//valid values: null (i.e. default), 'none' no rules, 'all' an audit rule with all system calls
			rulesType = argsMap.get("syscall");
			if(rulesType != null && !rulesType.equals("none") && !rulesType.equals("all")){
				logger.log(Level.SEVERE, "Invalid value for 'rules' argument: " + rulesType);
				return false;
			}
			
			spadeAuditBridgeCommand = spadeAuditBridgeBinaryPath + 
					((CREATE_BEEP_UNITS) ? " -u" : "") + 
					((mergeUnit != null) ? " -m " + mergeUnit : "") +
					// Don't use WAIT_FOR_LOG_END here because the interrupt would be ignored by spadeAuditBridge then
					" -s " + "/var/run/audispd_events";
			
		}
		
		// used to identify failure and do cleanup.
		boolean success = true;
		
		if(success){
			try{
				artifactManager = new ArtifactManager(this, globals);
			}catch(Exception e){
				logger.log(Level.SEVERE, "Failed to instantiate artifact manager", e);
				success = false;
			}
		}
		
		if(success){
			try{
				if(AGENTS){ // Make sure that this is done before starting the event reader thread
					processManager = new ProcessWithoutAgentManager(this, SIMPLIFY, CREATE_BEEP_UNITS, HANDLE_NAMESPACES);
				}else{
					processManager = new ProcessWithAgentManager(this, SIMPLIFY, CREATE_BEEP_UNITS, HANDLE_NAMESPACES);
				}
			}catch(Exception e){
				logger.log(Level.SEVERE, "Failed to instantiate process manager", e);
				success = false;
			}
		}
		
		if(success){
			if(isLiveAudit){
				// if live audit and no km but handling records then error
				if(!ADD_KM && HANDLE_KM_RECORDS){ // in case of live audit HANDLE_KM_RECORDS will never be null
					logger.log(Level.SEVERE, "Can't handle kernel module data without kernel module added for Live Audit");
					success = false;
				}
			}
		}
		
		if(success){
			if(HANDLE_NETFILTER_HOOKS){
				try{
					netfilterHooksManager = new NetfilterHooksManager(this, HANDLE_NAMESPACES);
				}catch(Exception e){
					logger.log(Level.SEVERE, "Failed to instantiate netfilter hooks manager", e);
					success = false;
				}
			}
		}
		
		if(success){
			try{
				
				java.lang.Process spadeAuditBridgeProcess = runSpadeAuditBridge(spadeAuditBridgeCommand);
				
				Thread errorReaderThread = getErrorStreamReaderForProcess(spadeAuditBridgeBinaryName, 
						spadeAuditBridgeProcess.getErrorStream());
				errorReaderThread.start();
				
				AuditEventReader auditEventReader = getAuditEventReader(spadeAuditBridgeCommand,
						spadeAuditBridgeProcess.getInputStream(), outputLogFilePath,
						recordsToRotateOutputLogAfter);
				
				Thread auditEventReaderThread = getAuditEventReaderThread(spadeAuditBridgeBinaryName, 
						auditEventReader, 
						isLiveAudit, rulesType, logListFile);
				auditEventReaderThread.start();
				
				try{ Thread.sleep(PID_MSG_WAIT_TIMEOUT); }catch(Exception e){}
				
				if(spadeAuditBridgeProcessPid == null){
					// still didn't get the pid that means the process didn't start successfully
					logger.log(Level.SEVERE, "Process didn't start successfully");
					success = false;
				}
				
			}catch(Exception e){
				logger.log(Level.SEVERE, "Failed to start Audit", e);
				success = false;
			}
		}
		
		if(success){
			if(isLiveAudit){
				
				if(success){
					if(ADD_KM || rulesType == null || rulesType.equals("all")){
						String uid = null;
						boolean ignoreUid; // if true then exclude the user else only include the given user
						String argsUsername = argsMap.get("user");
						if(argsUsername == null){
							ignoreUid = true;
							uid = getOwnUid();
						}else{
							ignoreUid = false;
							uid = checkIfValidUsername(argsUsername);
						}
						if(uid != null){
							String ignoreProcesses = "auditd kauditd audispd " + spadeAuditBridgeBinaryName;
							List<String> pidsToIgnore = listOfPidsToIgnore(ignoreProcesses);
							if(pidsToIgnore != null){
								
								String ignoreProcessesValueFromConfig = configMap.get("ignoreProcesses");
								if(ignoreProcessesValueFromConfig != null){
									String[] ignoreProcessesArray = ignoreProcessesValueFromConfig.split(",");
									for(String ignoreProcess : ignoreProcessesArray){
										namesOfProcessesToIgnoreFromConfig.add(ignoreProcess.trim());
									}
								}
								
								List<String> ppidsToIgnore = new ArrayList<String>(pidsToIgnore); // same as pids
								List<String> pidsToIgnoreFromConfig = getPidsFromConfig(configMap, "ignoreProcesses");
								List<String> ppidsToIgnoreFromConfig = getPidsFromConfig(configMap, "ignoreParentProcesses");
								if(pidsToIgnoreFromConfig != null){ // optional
									pidsToIgnore.addAll(pidsToIgnoreFromConfig);
									logger.log(Level.INFO, "Ignoring pids {0} for processes with names from config: {1}",
											new Object[]{pidsToIgnoreFromConfig, configMap.get("ignoreProcesses")});
								}
								if(ppidsToIgnoreFromConfig != null){ // optional
									ppidsToIgnore.addAll(ppidsToIgnoreFromConfig);
									logger.log(Level.INFO, "Ignoring ppids {0} for processes with names from config: {1}",
											new Object[]{ppidsToIgnoreFromConfig, configMap.get("ignoreParentProcesses")});
								}
								
								if(ADD_KM){
									success = addNetworkKernelModule(kernelModulePath, kernelModuleControllerPath, 
											uid, ignoreUid, pidsToIgnore, ppidsToIgnore, USE_SOCK_SEND_RCV, HARDEN,
											NETFILTER_HOOKS, NETFILTER_HOOKS_LOG_CT);
								}
								if(success){
									if(success){
										success = setAuditControlRules(rulesType, uid, ignoreUid, pidsToIgnore, 
												ppidsToIgnore, ADD_KM);
									}
								}
							}else{
								success = false;
							}
						}else{
							success = false;
						}
					}
				}
			}
		}
		
		if(success){
			return true;
		}else{
			// The spadeAuditBridge might have started
			if(spadeAuditBridgeProcessPid != null){
				sendSignalToPid(spadeAuditBridgeProcessPid, "9"); // force kill since Audit not added
			}
			doCleanup(rulesType, logListFile);
			return false;
		}
	}
	
	private List<String> getPidsFromConfig(Map<String, String> configMap, String processNamesKey){
		if(configMap != null && processNamesKey != null){
			String processNames = configMap.get(processNamesKey);
			if(processNames != null){
				processNames = processNames.trim();
				if(!processNames.isEmpty()){
					// The value is comma-separated. Replacing ',' with ' ' because that is the format
					// expected by the 'pidof' command.
					processNames = processNames.replace(',', ' ');
					List<String> pids = listOfPidsToIgnore(processNames); // Can return null;
					return pids;
				}
			}
		}
		return null;
	}

	public final ProcessManager getProcessManager(){
		return processManager;
	}
	
	public final ArtifactManager getArtifactManager(){
		return artifactManager;
	}
	
	public final IPCManager getIPCManager(){
		return ipcManager;
	}

	private AuditEventReader getAuditEventReader(String spadeAuditBridgeCommand, 
			InputStream stdoutStream,
			String outputLogFilePath,
			Long recordsToRotateOutputLogAfter){
		
		try{
			// Create the audit event reader using the STDOUT of the spadeAuditBridge process
			AuditEventReader auditEventReader = new AuditEventReader(spadeAuditBridgeCommand, 
					stdoutStream);
			if(outputLogFilePath != null){
				auditEventReader.setOutputLog(outputLogFilePath, recordsToRotateOutputLogAfter);
			}
			return auditEventReader;
		}catch(Exception e){
			logger.log(Level.SEVERE, "Failed to create audit event reader", e);
			return null;
		}
	}
	
	private boolean sendSignalToPid(String pid, String signal){
		try{
			Runtime.getRuntime().exec("kill -" + signal + " " + pid);
			return true;
		}catch(Exception e){
			logger.log(Level.WARNING, "Failed to send signal '"+signal+"' to pid '"+pid+"'", e);
			return false;
		}
	}
	
	private Thread getErrorStreamReaderForProcess(final String processName, final InputStream errorStream){
		try{
			
			Thread errorStreamReaderThread = new Thread(new Runnable(){
				public void run(){
					BufferedReader errorStreamReader = null;
					try{
						errorStreamReader = new BufferedReader(new InputStreamReader(errorStream));
						String line = null;
						while((line = errorStreamReader.readLine()) != null){
							if(line.startsWith("#CONTROL_MSG#")){
								spadeAuditBridgeProcessPid = line.split("=")[1];
							}else{
								logger.log(Level.INFO, processName + " output: " + line);
							}
						}
					}catch(Exception e){
						logger.log(Level.WARNING, "Failed to read error stream for process: " + processName);
					}finally{
						if(errorStreamReader != null){
							try{
								errorStreamReader.close();
							}catch(Exception e){
								//ignore
							}
						}
					}
					logger.log(Level.INFO, "Exiting error reader thread for process: " + processName);
				}
			});
			
			return errorStreamReaderThread;
			
		}catch(Exception e){
			logger.log(Level.SEVERE, "Failed to create error reader thread for " + processName, e);
			return null;
		}
	}
	
	private Thread getAuditEventReaderThread(final String processName, final AuditEventReader auditEventReader, 
			final boolean isLiveAudit, final String rulesType, final String logListFile){
		Runnable runnable = new Runnable() {
			
			@Override
			public void run() {
				
				eventReaderThreadRunning = true;
				
				if(isLiveAudit){
					if(PROCFS){
						// Proc filesystem read not done
//						processManager.putProcessesFromProcFs();
					}
				}
				
				while(true){
					Map<String, String> eventData = null;
					try{
						eventData = auditEventReader.readEventData();
						if(eventData == null){
							// EOF
							break;
						}else{
							try{
								finishEvent(eventData);
							}catch(Exception e){
								logger.log(Level.SEVERE, "Failed to handle event: " + eventData, e);
								if(FAIL_FAST){
									break;
								}
							}
						}
					}catch(MalformedAuditDataException made){
						logger.log(Level.SEVERE, "Failed to parse event", made);
						if(FAIL_FAST){
							break;
						}
					}catch(Exception e){
						logger.log(Level.SEVERE, "Stopped reading event stream. ", e);
						break;
					}
				}
				try{
					if(auditEventReader != null){
						auditEventReader.close();
					}
				}catch(Exception e){
					logger.log(Level.WARNING, "Failed to close audit event reader", e);
				}
				
				// Sent a signal to the process in shutdown to stop reading.
				// That's why here.
				doCleanup(rulesType, logListFile);
				logger.log(Level.INFO, "Exiting event reader thread for process: " + processName);
				eventReaderThreadRunning = false;
			}
		};
		return new Thread(runnable);
	}

	private java.lang.Process runSpadeAuditBridge(String command){
		try{
			java.lang.Process spadeAuditBridgeProcess = Runtime.getRuntime().exec(command);
			logger.log(Level.INFO, "Succesfully executed the command: '" + command + "'");
			return spadeAuditBridgeProcess;
		}catch(Exception e){
			logger.log(Level.SEVERE, "Failed to execute command: '" + command + "'", e);
			return null;
		}
	}
	
	private Boolean kernelModuleExists(String kernelModuleName){
		try{
			Execute.Output output = Execute.getOutput("lsmod");
			if(output.hasError()){
				output.log();
				return null;
			}else{
				List<String> stdOutLines = output.getStdOut();
				for(String line : stdOutLines){
					String[] tokens = line.split("\\s+");
					if(tokens[0].equals(kernelModuleName)){
						return true;
					}
				}
				return false;
			}
		}catch(Exception e){
			logger.log(Level.SEVERE, "Failed to check if module '"+kernelModuleName+"' exists", e);
			return null;
		}
	}
	
	private String getKernelModuleName(String kernelModulePath){
		if(kernelModulePath != null){
			try{
				String tokens[] = kernelModulePath.split("/");
				String name = tokens[tokens.length - 1];
				tokens = name.split("\\.");
				name = tokens[0];
				return name;
			}catch(Exception e){
				logger.log(Level.SEVERE, "Failed to get module name for: "+ kernelModulePath, e);
				return null;
			}
		}else{
			return null;
		}
	}
	
	private boolean addKernelModule(String command){
		try{
			Execute.Output output = Execute.getOutput(command);
			if(!output.getStdErr().isEmpty()){
				logger.log(Level.SEVERE, "Command \"{0}\" failed with error: {1}.", new Object[]{
						command, output.getStdErr()});
				logger.log(Level.SEVERE, "Run 'grep netio <dmesg-logs> | tail -5' to check for exact error.");
				return false;
			}else{
				if(HARDEN){ // hide the key
					int endIndex = command.indexOf(" key=");
					endIndex = endIndex != -1 ? endIndex : command.length();
					command = command.substring(0, endIndex);
					logger.log(Level.INFO, "Command \"{0}\" succeeded with output: {1}.", new Object[]{
							command, output.getStdOut()});
				}else{
					logger.log(Level.INFO, "Command \"{0}\" succeeded with output: {1}.", new Object[]{
							command, output.getStdOut()});
				}
				return true;
			}
		}catch(Exception e){
			logger.log(Level.SEVERE, "Failed to add kernel module with command: " + command, e);
			return false;
		}
	}
	
	private boolean addNetworkKernelModule(String kernelModulePath, String kernelModuleControllerPath, 
			String uid, boolean ignoreUid, List<String> ignorePids, List<String> ignorePpids, boolean interceptSendRecv,
			boolean harden, boolean netfilterHooks, boolean netfilterHooksLogCt){
		if(uid == null || uid.isEmpty() || ignorePids == null || ignorePids.isEmpty()
				|| ignorePpids == null || ignorePpids.isEmpty()){
			logger.log(Level.SEVERE, "Invalid args. uid={0}, pids={1}, ppids={2}", new Object[]{uid, ignorePids, ignorePpids});
			return false;
		}else{
			String hardenTgidsArgumentList = null;
			try{
				if(!FileUtility.isFileReadable(kernelModulePath)){
					logger.log(Level.SEVERE, "Kernel module path not readable: " + kernelModulePath);
					return false;
				}
			}catch(Exception e){
				logger.log(Level.SEVERE, "Failed to check if kernel module path is readable: " + kernelModulePath, e);
				return false;
			}
			try{
				if(!FileUtility.isFileReadable(kernelModuleControllerPath)){
					logger.log(Level.SEVERE, "Controller kernel module path not readable: " + kernelModuleControllerPath);
					return false;
				}
			}catch(Exception e){
				logger.log(Level.SEVERE, "Failed to check if controller kernel module path is readable: " + kernelModuleControllerPath, e);
				return false;
			}
			
			if(harden){
				Set<String> tgidsToHarden = null;
				try{
					tgidsToHarden = getTgidsOfProcessesToHarden();
				}catch(Exception e){
					logger.log(Level.SEVERE, "Failed to get tgids of processes to harden", e);
					return false;
				}
				
				if(tgidsToHarden != null && !tgidsToHarden.isEmpty()){
					hardenTgidsArgumentList = "";
					try{
						for(String tgidToHarden : tgidsToHarden){
							hardenTgidsArgumentList += tgidToHarden + ",";
						}
						if(!hardenTgidsArgumentList.isEmpty()){
							hardenTgidsArgumentList = hardenTgidsArgumentList.substring(0, hardenTgidsArgumentList.length() - 1);
						}
					}catch(Exception e){
						logger.log(Level.SEVERE, "Failed to get tgids of processes to harden", e);
						return false;
					}
				}
				
				String data = String.valueOf(System.nanoTime()) + "&" + String.valueOf(Math.random());
				kernelModuleKey = DigestUtils.md5Hex(data);
			}
			
			String kernelModuleName = getKernelModuleName(kernelModulePath);
			if(kernelModuleName != null){
				String kernelModuleControllerName = getKernelModuleName(kernelModuleControllerPath);
				if(kernelModuleControllerName != null){
					Boolean kernelModuleControllerExists = kernelModuleExists(kernelModuleControllerName);
					if(kernelModuleControllerExists != null){
						if(kernelModuleControllerExists){
							logger.log(Level.SEVERE, "Kernel module controller '"+kernelModuleControllerPath+"' "
									+ "already exists.");
							return false;
						}else{
							Boolean kernelModuleExists = kernelModuleExists(kernelModuleName);
							if(kernelModuleExists != null){
								if(kernelModuleExists == false){
									// add the main kernel module
									String kernelModuleAddCommand = "insmod " + kernelModulePath;
									if(!addKernelModule(kernelModuleAddCommand)){
										return false;
									}
								}
								// add the controller kernel module
								StringBuffer pids = new StringBuffer();
								ignorePids.forEach(ignorePid -> {pids.append(ignorePid).append(",");});
								pids.deleteCharAt(pids.length() - 1);// delete trailing comma
								
								StringBuffer ppids = new StringBuffer();
								ignorePpids.forEach(ignorePpid -> {ppids.append(ignorePpid).append(",");});
								ppids.deleteCharAt(ppids.length() - 1);// delete trailing comma
								
								String ignoreUidsArg = ignoreUid ? "1" : "0"; // 0 is capture
								
								String kernelModuleControllerAddCommand = 
										String.format("insmod %s uids=\"%s\" syscall_success=\"1\" "
										+ "pids_ignore=\"%s\" ppids_ignore=\"%s\" net_io=\"%s\" "
										+ "ignore_uids=\"%s\" namespaces=\"%s\" "
										+ "nf_hooks=\"%s\" nf_hooks_log_all_ct=\"%s\" nf_handle_user=\"%s\"",
										kernelModuleControllerPath, uid, pids, ppids,
										interceptSendRecv ? "1" : "0", ignoreUidsArg, 
										HANDLE_NAMESPACES ? "1" : "0",
										netfilterHooks ? "1" : "0",
										netfilterHooksLogCt ? "1" : "0",
										NETFILTER_HOOKS_USER ? "1" : "0" );
								
								if(harden){
									kernelModuleControllerAddCommand += " key=\""+kernelModuleKey+"\"";
									if(hardenTgidsArgumentList != null && !hardenTgidsArgumentList.isEmpty()){
										kernelModuleControllerAddCommand += " harden_tgids=\""+hardenTgidsArgumentList+"\"";
									}
								}
								
								if(!addKernelModule(kernelModuleControllerAddCommand)){
									return false;
								}else{
									return true;
								}
							}
						}
					}
				}
			}
		}
		return false;
	}
	
	private boolean removeModuleByRmmodUtility(String moduleName){
		String command = "rmmod " + moduleName;
		try{
			Execute.Output output = Execute.getOutput(command);
			output.log();
			return !output.hasError();
		}catch(Exception e){
			logger.log(Level.SEVERE, "Failed to execute command: " + command, e);
			return false;
		}
	}
	
	private boolean removeModuleByDeleteModuleUtility(String moduleName){
		try{
			final java.lang.Process process = Runtime.getRuntime().exec(deleteModuleBinaryPath);
			
			Thread stdoutReaderThread = new Thread(new Runnable(){
				public void run(){
					try{
						InputStream stdout = process.getInputStream();
						BufferedReader br = new BufferedReader(new InputStreamReader(stdout));
						String line = null;
						while((line = br.readLine()) != null){
							logger.log(Level.INFO, deleteModuleBinaryPath + " output: " + line);
						}
					}catch(Exception e){
						logger.log(Level.SEVERE, "Failed to read standard out for process: " + deleteModuleBinaryPath, e);
					}
				}
			});
			
			Thread stderrReaderThread = new Thread(new Runnable(){
				public void run(){
					try{
						InputStream stderr = process.getErrorStream();
						BufferedReader br = new BufferedReader(new InputStreamReader(stderr));
						String line = null;
						while((line = br.readLine()) != null){
							logger.log(Level.SEVERE, deleteModuleBinaryPath + " error: " + line);
						}
					}catch(Exception e){
						logger.log(Level.SEVERE, "Failed to read standard error for process: " + deleteModuleBinaryPath, e);
					}
				}
			});
			
			try{
				stdoutReaderThread.start();
				stderrReaderThread.start();
			}catch(Exception e){
				logger.log(Level.SEVERE, "Failed to start readers for process '"+deleteModuleBinaryPath+"'", e);
				return false;
			}
			
			try{
				PrintWriter stdInWriter = new PrintWriter(process.getOutputStream());
				stdInWriter.println(moduleName);
				stdInWriter.flush();
				stdInWriter.close();
			}catch(Exception e){
				logger.log(Level.SEVERE, "Failed to write key to process '"+deleteModuleBinaryPath+"'");
				return false;
			}
			
			try{
				int resultValue = process.waitFor();
				if(resultValue == 0){ // success
					logger.log(Level.INFO, "Successfully removed netio_controller module using deleteModule");
					return true;
				}else{
					logger.log(Level.SEVERE, "Process '"+deleteModuleBinaryPath+"' executed with error code: " + resultValue);
					return false;
				}
			}catch(Exception e){
				logger.log(Level.SEVERE, "Error in process execution '"+deleteModuleBinaryPath+"'", e);
				return false;
			}
		}catch(Exception e){
			logger.log(Level.SEVERE, "Failed to remove module by syscall", e);
			return false;
		}
	}
	
	private boolean removeControllerNetworkKernelModule(){
		String controllerModulePath = kernelModuleControllerPath;
		if(HelperFunctions.isNullOrEmpty(controllerModulePath)){
			logger.log(Level.WARNING, "NULL/Empty controller kernel module path: " + controllerModulePath);
		}else{
			String controllerModuleName = getKernelModuleName(controllerModulePath);
			if(HelperFunctions.isNullOrEmpty(controllerModuleName)){
				logger.log(Level.SEVERE, "Failed to get module name from module path: " + controllerModulePath);
			}else{
				Boolean controllerModuleExists = kernelModuleExists(controllerModuleName);
				if(controllerModuleExists == null){
					logger.log(Level.SEVERE, "Failed to check if controller module '"+controllerModuleName+"' exists");
				}else{
					if(controllerModuleExists ==  false){
						logger.log(Level.INFO, "Controller kernel module not added : " + controllerModuleName);
					}else{
						if(!HARDEN){
							return removeModuleByRmmodUtility(controllerModuleName);
						}else{
							// Try removing using the key. If fails then do the normal value
							if(!removeModuleByDeleteModuleUtility(kernelModuleKey)){
								return removeModuleByRmmodUtility(controllerModuleName);
							}else{
								return true;
							}
						}
					}
				}
			}
		}
		return false;
	}
	
	private boolean setAuditControlRules(String rulesType, String uid, boolean ignoreUid, List<String> ignorePids, 
			List<String> ignorePpids, boolean kmAdded){
		try {

			if(uid == null || uid.isEmpty() || ignorePids == null || ignorePids.isEmpty()
					|| ignorePpids == null || ignorePpids.isEmpty()){
				logger.log(Level.SEVERE, "Invalid args. uid={0}, pids={1}, ppids={2}", new Object[]{uid, ignorePids,
						ignorePpids});
				return false;
			}
			
			if("none".equals(rulesType)){
				// do nothing
				return true;
			}else{
				
				List<String> auditRules = new ArrayList<String>();
			
				String auditRuleWithoutProctitle = "auditctl -a always,exclude -F msgtype=PROCTITLE";
				// Always the first rule
				auditRules.add(auditRuleWithoutProctitle);
				
				// Remove any existing audit rules
				if(!removeAuditctlRules()){
					return false;
				}
				
				// Set arch to use in the rules
				String archField = "-F arch=b64 ";
				
				String uidField = null;
				if(ignoreUid){ // ignore the given uid
					uidField = "-F uid!=" + uid + " ";
				}else{ // only capture the given uid
					uidField = "-F uid=" + uid + " ";
				}

				StringBuffer pidFields = new StringBuffer();
				ignorePids.forEach(ignorePid -> {pidFields.append("-F pid!=").append(ignorePid).append(" ");});
				
				StringBuffer ppidFields = new StringBuffer();
				ignorePpids.forEach(ignorePpid -> {ppidFields.append("-F ppid!=").append(ignorePpid).append(" ");});
				
				String pidAndPpidFields = pidFields.toString() + ppidFields.toString();
				
				if("all".equals(rulesType)){

					String netIONeverSyscallsRule = null;
					if(kmAdded){
						netIONeverSyscallsRule = "auditctl -a exit,never ";
						netIONeverSyscallsRule += archField;
						netIONeverSyscallsRule += "-S kill -S socket -S bind -S accept -S accept4 -S connect ";
						netIONeverSyscallsRule += "-S sendmsg -S sendto -S recvmsg -S recvfrom -S sendmmsg -S recvmmsg ";
					}
					
                    String specialSyscallsRule = "auditctl -a exit,always ";
					String allSyscallsAuditRule = "auditctl -a exit,always ";
					
					allSyscallsAuditRule += archField;
					specialSyscallsRule += archField;
					
					allSyscallsAuditRule += "-S all ";
					// The connect syscall rule won't be matched even if module added because the 'never' rule is before this one
					specialSyscallsRule += "-S exit -S exit_group -S kill -S connect ";

					allSyscallsAuditRule += uidField;
					specialSyscallsRule += uidField;

					allSyscallsAuditRule += "-F success=" + AUDITCTL_SYSCALL_SUCCESS_FLAG + " ";
					
					// THE NEVER RULE SHOULD ALWAYS BE THE FIRST IF IT IS INITIALIZED
					if(kmAdded && netIONeverSyscallsRule != null){
						auditRules.add(netIONeverSyscallsRule);
					}
					auditRules.add(specialSyscallsRule + pidAndPpidFields);
					auditRules.add(allSyscallsAuditRule + pidAndPpidFields);

				}else if(rulesType == null){

					String auditRuleWithoutSuccess = "auditctl -a exit,always ";
					String auditRuleWithSuccess = "auditctl -a exit,always ";
					
					auditRuleWithSuccess += archField;
					auditRuleWithoutSuccess += archField;

					auditRuleWithSuccess += uidField;
					auditRuleWithoutSuccess += uidField;

					auditRuleWithoutSuccess += "-S exit -S exit_group ";
					if(!kmAdded){
						auditRuleWithoutSuccess += "-S connect -S kill ";
					}

					if (USE_READ_WRITE) {
						auditRuleWithSuccess += "-S read -S readv -S pread -S preadv -S write -S writev -S pwrite -S pwritev -S lseek ";
					}
					if(!kmAdded){ // since km not added we don't need to log these syscalls
						if (USE_SOCK_SEND_RCV) {
							auditRuleWithSuccess += "-S sendto -S recvfrom -S sendmsg -S recvmsg ";
						}
						auditRuleWithSuccess += "-S bind -S accept -S accept4 -S socket ";
					}
					if (USE_MEMORY_SYSCALLS) {
						auditRuleWithSuccess += "-S mmap -S mprotect -S madvise ";
					}
					auditRuleWithSuccess += "-S unlink -S unlinkat ";
					auditRuleWithSuccess += "-S link -S linkat -S symlink -S symlinkat ";
					auditRuleWithSuccess += "-S clone -S fork -S vfork -S execve ";
					auditRuleWithSuccess += "-S open -S close -S creat -S openat -S mknodat -S mknod ";
					auditRuleWithSuccess += "-S dup -S dup2 -S dup3 ";
					auditRuleWithSuccess += "-S fcntl ";
					auditRuleWithSuccess += "-S rename -S renameat ";
					auditRuleWithSuccess += "-S setuid -S setreuid ";
					auditRuleWithSuccess += "-S setgid -S setregid ";
					if(!SIMPLIFY){
						auditRuleWithSuccess += "-S setresuid -S setfsuid ";
						auditRuleWithSuccess += "-S setresgid -S setfsgid ";
					}
					auditRuleWithSuccess += "-S chmod -S fchmod -S fchmodat ";
					auditRuleWithSuccess += "-S pipe -S pipe2 ";
					auditRuleWithSuccess += "-S truncate -S ftruncate ";
					auditRuleWithSuccess += "-S init_module -S finit_module ";
					auditRuleWithSuccess += "-S tee -S splice -S vmsplice ";
					auditRuleWithSuccess += "-S socketpair ";
					auditRuleWithSuccess += "-S ptrace ";
					if(HANDLE_CHDIR){
						auditRuleWithSuccess += "-S chdir -S fchdir ";
					}
					if(HANDLE_ROOTFS){
						auditRuleWithSuccess += "-S chroot -S pivot_root ";
					}
					if(HANDLE_NAMESPACES){
						auditRuleWithSuccess += "-S setns -S unshare ";
					}
					if(CAPTURE_IPC){
						final List<String> ipcSyscalls = IPCManager.getSyscallNamesForAuditctlForAll();
						String subRuleSyscalls = "";
						for(final String ipcSyscall : ipcSyscalls){
							subRuleSyscalls += "-S " + ipcSyscall + " ";
						}
						auditRuleWithSuccess += subRuleSyscalls;
					}
					
					auditRuleWithSuccess += "-F success=" + AUDITCTL_SYSCALL_SUCCESS_FLAG + " ";

					auditRules.add(auditRuleWithoutSuccess + pidAndPpidFields);
					auditRules.add(auditRuleWithSuccess + pidAndPpidFields);
				}else{
					logger.log(Level.SEVERE, "Invalid rules arguments: " + rulesType);
					return false;
				}

				// Execute in provided order!
				for(String auditRule : auditRules){
					if(!executeAuditctlRule(auditRule)){
						removeAuditctlRules();
						return false;
					}
				}
			}

			return true;
		} catch (Exception e) {
			logger.log(Level.SEVERE, "Error configuring audit rules", e);
			return false;
		}

	}

	private boolean executeAuditctlRule(String auditctlRule){
		try{
			Execute.Output output = Execute.getOutput(auditctlRule);
			output.log();
			return !output.hasError();
		}catch(Exception e){
			logger.log(Level.SEVERE, "Failed to set audit rule: " + auditctlRule, e);
			return false;
		}
	}

	private boolean removeAuditctlRules(){
		try{
			Execute.Output output = Execute.getOutput("auditctl -D");
			output.log();
			return !output.hasError();
		}catch(Exception e){
			logger.log(Level.SEVERE, "Failed to remove audit rules", e);
			return false;
		}
	}
	
	private static Set<String> getTgidsOfProcessesToHarden() throws Exception{
		Set<String> tgids = new HashSet<String>();
		String processNames[] = {"auditd", "audispd", "kauditd", "spadeAuditBridge"};
		for(String processName : processNames){
			Set<String> pids = pidOf(processName);
			for(String pid : pids){
				String tgid = getTgid(pid);
				tgids.add(tgid);
			}
		}
		tgids.add(getSelfTgid());
		return tgids;
	}
	
	private static Set<String> pidOf(String processName) throws Exception{
		String command = "pidof " + processName;
		Output output = Execute.getOutput(command);
		if(output.hasError()){
			throw new Exception("Command failed: " + command);
		}else{
			Set<String> pids = new HashSet<String>();
			List<String> lines = output.getStdOut();
			if(lines.isEmpty()){
				throw new Exception("No output for command: " + command);
			}else{
				String pidsLine = lines.get(0);
				String pidsArray[] = pidsLine.split("\\s+");
				if(pidsArray.length == 0){
					throw new Exception("Empty output for command: " + command);
				}else{
					for(String pid : pidsArray){
						pids.add(pid);
					}
					return pids;
				}
			}
		}
	}
	
	private static String getSelfTgid() throws Exception{
		return getTgid("self");
	}
	
	private static String getTgid(String pid) throws Exception{
		String procPath = "/proc/"+pid+"/status";
		List<String> lines = FileUtility.readLines(procPath);
		for(String line : lines){
			line = line.toLowerCase().trim();
			String tokens[] = line.split(":");
			if(tokens.length >= 2){
				String name = tokens[0].trim();
				String value = tokens[1].trim();
				if(name.equals("tgid") && !value.isEmpty()){
					return value;
				}
			}
		}
		throw new Exception("No 'tgid' key found in file: " + procPath);
	}
	
	private List<String> listOfPidsToIgnore(String ignoreProcesses){
//		ignoreProcesses argument is a string of process names separated by blank space
		BufferedReader pidReader = null;
		try{
			List<String> pids = new ArrayList<String>();
			if(ignoreProcesses != null && !ignoreProcesses.trim().isEmpty()){
				// Using pidof command now to get all pids of the mentioned processes
				java.lang.Process pidChecker = Runtime.getRuntime().exec("pidof " + ignoreProcesses);
				// pidof returns pids of given processes as a string separated by a blank space
				pidReader = new BufferedReader(new InputStreamReader(pidChecker.getInputStream()));
				String pidline = pidReader.readLine();
				if(pidline != null){
					// 	added all returned from pidof command
					pids.addAll(Arrays.asList(pidline.split("\\s+")));
				}else{
					logger.log(Level.INFO, "No running process(es) with name(s): " + ignoreProcesses);
				}
			}
			return pids;
		}catch(Exception e){
			logger.log(Level.WARNING, "Error building list of processes to ignore: " + ignoreProcesses, e);
			return null;
		}finally{
			if(pidReader != null){
				try{
					pidReader.close();
				}catch(Exception e){
					// ignore
				}
			}
		}
	}

	// Returns the uid if valid username
	private String checkIfValidUsername(String name){
		String command = "id -u " + name;
		try{
			Execute.Output output = Execute.getOutput(command);
			if(output.hasError()){
				logger.log(Level.SEVERE, "Invalid username provided. Command: {0}. Error: {1}", 
						new Object[]{command, output.getStdErr()});
			}else{
				List<String> stdOutLines = output.getStdOut();
				if(stdOutLines.size() == 0){
					logger.log(Level.SEVERE, "No uid in output for command: {0}. Output: {1}.", new Object[]{
							command, stdOutLines
					});
				}else{
					String uidLine = stdOutLines.get(0);
					if(uidLine == null || (uidLine = uidLine.trim()).isEmpty()){
						logger.log(Level.SEVERE, "NULL/Empty uid for command: {0}. Output: {1}.", new Object[]{
								command, stdOutLines
						});
					}else{
						return uidLine;
					}
				}
			}
		}catch(Exception e){
			logger.log(Level.SEVERE, "Failed to execute command: " + command, e);
		}
		return null;
	}
	
	private String getOwnUid(){
		String command = "id -u";
		try{
			String uid = null;
			Execute.Output output = Execute.getOutput(command);
			if(output.hasError()){
				logger.log(Level.SEVERE, "Failed to get user id of JVM. Command: {0}. Error: {1}.",
						new Object[]{command, output.getStdErr()});
				return null;
			}else{
				List<String> stdOutLines = output.getStdOut();
				if(stdOutLines.size() == 0){
					logger.log(Level.SEVERE, "No uid in output for command: {0}. Output: {1}.", new Object[]{
							command, stdOutLines
					});
					return null;
				}else{
					String uidLine = stdOutLines.get(0);
					if(uidLine == null || (uidLine = uidLine.trim()).isEmpty()){
						logger.log(Level.SEVERE, "NULL/Empty uid for command: {0}. Output: {1}.", new Object[]{
								command, stdOutLines
						});
						return null;
					}else{
						uid = uidLine;
					}
				}
				return uid;
			}
		}catch(Exception e){
			logger.log(Level.SEVERE, "Failed to get user id of JVM using command: " + command, e);
			return null;
		}
	}

	@Override
	public boolean shutdown() {
		
		// Remove the kernel module first because we need to kill spadeAuditBridge
		// because it might be hardened
		if(ADD_KM){
			if(removeControllerNetworkKernelModule()){
				logger.log(Level.INFO, "Successfully removed the kernel controller module");
			}
		}
		
		// Send an interrupt to the spadeAuditBridgeProcess
		
		sendSignalToPid(spadeAuditBridgeProcessPid, "2");
		
		// Return. The event reader thread and the error reader thread will exit on their own.
		// The event reader thread will do the state cleanup
		
		logger.log(Level.INFO, "Going to wait for event reader thread to finish");
		
		while(eventReaderThreadRunning){
			// Wait while the event reader thread is still running i.e. buffer being emptied
			try{ Thread.sleep(PID_MSG_WAIT_TIMEOUT); }catch(Exception e){}
		}
		
		// force print stats before exiting
		printStats(true);
		
		return true;
	}

	private void printStats(boolean forcePrint){
		if(reportingEnabled || forcePrint){
			long currentTime = System.currentTimeMillis();
			if(((currentTime - lastReportedTime) >= reportEveryMs) || forcePrint){
				Runtime runtime = Runtime.getRuntime();
				long usedMemoryMB = (runtime.totalMemory() - runtime.freeMemory()) / (1024*1024);   	
				int internalBufferSize = getBuffer().size();
				String statString = String.format("Internal buffer size: %d, JVM memory in use: %dMB", 
						internalBufferSize, usedMemoryMB);
				logger.log(Level.INFO, statString);
				
				if(HANDLE_NETFILTER_HOOKS){
					if(netfilterHooksManager != null){
						netfilterHooksManager.printStats();
					}
				}
				
				lastReportedTime = currentTime;
			}
		}
	}
	
	private void setHandleKMRecordsFlag(boolean isLiveAudit, boolean valueOfHandleKMRecords){
		// Only set the value if it hasn't been set and is log playback
		if(HANDLE_KM_RECORDS == null && !isLiveAudit){
			HANDLE_KM_RECORDS = valueOfHandleKMRecords;
			logger.log(Level.INFO, "'handleLocalEndpoints' value set to '"+valueOfHandleKMRecords+"'");
		}
	}

	private void finishEvent(Map<String, String> eventData){

		printStats(false);

		if (eventData == null) {
			logger.log(Level.WARNING, "Null event data read");
			return;
		}

		try{
			String recordType = eventData.get(AuditEventReader.RECORD_TYPE_KEY);
			if(AuditEventReader.RECORD_TYPE_UBSI_ENTRY.equals(recordType)){
				processManager.handleUnitEntry(eventData);
			}else if(AuditEventReader.RECORD_TYPE_UBSI_EXIT.equals(recordType)){
				processManager.handleUnitExit(eventData);
			}else if(AuditEventReader.RECORD_TYPE_UBSI_DEP.equals(recordType)){
				processManager.handleUnitDependency(eventData);
			}else if(AuditEventReader.RECORD_TYPE_DAEMON_START.equals(recordType)){
				//processManager.daemonStart(); TODO Not being used until figured out how to handle it.
			}else if(AuditEventReader.KMODULE_RECORD_TYPE.equals(recordType)){
				setHandleKMRecordsFlag(isLiveAudit, true); // Always do first because HANDLE_KM_RECORDS can be null when playback
				if(HANDLE_KM_RECORDS){
					handleKernelModuleEvent(eventData);
				}
			}else if(AuditEventReader.RECORD_TYPE_NETFILTER_HOOK.equals(recordType)){
				if(HANDLE_NETFILTER_HOOKS){
					netfilterHooksManager.handleNetfilterHookEvent(eventData);
				}
			}else{
				handleSyscallEvent(eventData);
			}
		}catch(Exception e){
			logger.log(Level.WARNING, "Failed to process eventData: " + eventData, e);
		}
	}
	
	private void handleKernelModuleEvent(Map<String, String> eventData){
		String eventId = eventData.get(AuditEventReader.EVENT_ID);
		String time = eventData.get(AuditEventReader.TIME);
		SYSCALL syscall = null;
		try{
			String pid = eventData.get(AuditEventReader.PID);
			Integer syscallNumber = HelperFunctions.parseInt(eventData.get(AuditEventReader.SYSCALL), null);
			String exit = eventData.get(AuditEventReader.EXIT);
			int success = HelperFunctions.parseInt(eventData.get(AuditEventReader.SUCCESS), -1);
			String sockFd = eventData.get(AuditEventReader.KMODULE_FD);
			int sockType = Integer.parseInt(eventData.get(AuditEventReader.KMODULE_SOCKTYPE));
			String localSaddr = eventData.get(AuditEventReader.KMODULE_LOCAL_SADDR);
			String remoteSaddr = eventData.get(AuditEventReader.KMODULE_REMOTE_SADDR);
			
			if(success == 1){
				syscall = getSyscall(syscallNumber);
				if(syscall == null || syscall == SYSCALL.UNSUPPORTED){
					log(Level.WARNING, "Invalid syscall: " + syscallNumber, null, time, eventId, null);
				}else{
					switch (syscall) {
						case BIND:
							handleBindKernelModule(eventData, time, eventId, syscall, pid,
									exit, sockFd, sockType, localSaddr, remoteSaddr);
							break;
						case ACCEPT:
						case ACCEPT4:
							handleAcceptKernelModule(eventData, time, eventId, syscall, pid, 
									exit, sockFd, sockType, localSaddr, remoteSaddr);
							break;
						case CONNECT:
							handleConnectKernelModule(eventData, time, eventId, syscall, pid,
									exit, sockFd, sockType, localSaddr, remoteSaddr);
							break;
						case SENDMSG:
						case SENDTO:
//						case SENDMMSG: // TODO
							handleNetworkIOKernelModule(eventData, time, eventId, syscall, pid, exit, sockFd, 
									sockType, localSaddr, remoteSaddr, false);
							break;
						case RECVMSG:
						case RECVFROM:
//						case RECVMMSG:
							handleNetworkIOKernelModule(eventData, time, eventId, syscall, pid, exit, sockFd, 
									sockType, localSaddr, remoteSaddr, true);
							break;
						default:
							log(Level.WARNING, "Unexpected syscall: " + syscallNumber, null, time, eventId, syscall);
							break;
					}
				}
			}
		}catch(Exception e){
			log(Level.WARNING, "Failed to parse kernel module event", null, time, eventId, syscall);
		}
	}
	
	private SYSCALL getSyscall(int syscallNumber){
		return SYSCALL.get64BitSyscall(syscallNumber);
	}
	
	/**
	 * Converts syscall args: 'a0', 'a1', 'a2', and 'a3' from hexadecimal values to decimal values
	 * 
	 * Conversion done based on the length of the hex value string. If length <= 8 then integer else a long.
	 * If length > 16 then truncated to long.
	 * 
	 * Done so to avoid the issue of incorrectly fitting a small negative (i.e. int) value into a big (i.e. long) value
	 * causing a wrong interpretation of bits.
	 * 
	 * @param eventData map that contains the above-mentioned args keys and values
	 * @param time time of the event
	 * @param eventId id of the event
	 * @param syscall syscall of the event
	 */
	private void convertArgsHexToDec(Map<String, String> eventData, String time, String eventId, SYSCALL syscall){
		String[] argKeys = {AuditEventReader.ARG0, AuditEventReader.ARG1, AuditEventReader.ARG2, AuditEventReader.ARG3};
		for(String argKey : argKeys){
			String hexArgValue = eventData.get(argKey);
			if(hexArgValue != null){
				int hexArgValueLength = hexArgValue.length();
				try{
					BigInteger bigInt = new BigInteger(hexArgValue, 16);
					String argValueString = null;
					if(hexArgValueLength <= 8){
						int argInt = bigInt.intValue();
						argValueString = Integer.toString(argInt);
					}else{ // greater than 8
						if(hexArgValueLength > 16){
							log(Level.SEVERE, "Truncated value for '" + argKey + "': '"+hexArgValue+"'. Too big for 'long' datatype", null, time, eventId, syscall);
						}
						long argLong = bigInt.longValue();
						argValueString = Long.toString(argLong);
					}
					eventData.put(argKey, argValueString);
				}catch(Exception e){
					log(Level.SEVERE, "Non-numerical value for '" + argKey + "': '"+hexArgValue+"'", e, time, eventId, syscall);
				}
			}else{
				log(Level.SEVERE, "NULL value for '" + argKey + "'", null, time, eventId, syscall);
			}
		}
	}
	
	/**
	 * Gets the key value map from the internal data structure and gets the system call from the map.
	 * Gets the appropriate system call based on current architecture
	 * If global flag to log only successful events is set to true but the current event wasn't successful then only handle it if was either a kill
	 * system call or exit system call or exit_group system call.
	 * 
	 * IMPORTANT: Converts all 4 arguments, a0 o a3 to decimal integers from hexadecimal integers and puts them back in the key value map
	 * 
	 * Calls the appropriate system call handler based on the system call
	 * 
	 * @param eventId id of the event against which the key value maps are saved
	 */
	private void handleSyscallEvent(Map<String, String> eventData) {
		String time = eventData.get(AuditEventReader.TIME);
		String eventId = eventData.get(AuditEventReader.EVENT_ID);
		try {
			processManager.processSeenInUnsupportedSyscall(eventData); // Always set first because that is what is done in spadeAuditBridge and it is updated if syscall handled.
			
			int syscallNum = HelperFunctions.parseInt(eventData.get(AuditEventReader.SYSCALL), -1);
			
			if(syscallNum == -1){
				return;
			}
			
			SYSCALL syscall = getSyscall(syscallNum);
			
			if(syscall == null){
				log(Level.WARNING, "Invalid syscall: " + syscallNum, null, time, eventId, null);
				return;
			}else if(syscall == SYSCALL.UNSUPPORTED){
				return;
			}

			if("1".equals(AUDITCTL_SYSCALL_SUCCESS_FLAG) 
					&& AuditEventReader.SUCCESS_NO.equals(eventData.get(AuditEventReader.SUCCESS))){
				//if only log successful events but the current event had success no then only monitor the following calls.
				if(syscall == SYSCALL.EXIT || syscall == SYSCALL.EXIT_GROUP
						|| syscall == SYSCALL.CONNECT){
					//continue and log these syscalls irrespective of success
					// Syscall connect can fail with EINPROGRESS flag which we want to 
					// mark as successful even though we don't know yet
				}else{ //for all others don't log
					return;
				}
			}

			//convert all arguments from hexadecimal format to decimal format and replace them. done for convenience here and to avoid issues. 
			convertArgsHexToDec(eventData, time, eventId, syscall);
			
			// Check if one of the network related syscalls. Must do this check before because HANDLE_KM_RECORDS can be null
			switch (syscall) {
				case SENDMSG:
				case SENDTO:
				case RECVFROM: 
				case RECVMSG:
				case SOCKET:
				case BIND:
				case ACCEPT:
				case ACCEPT4:
				case CONNECT:
					setHandleKMRecordsFlag(isLiveAudit, false);
					break;
				default:
					break;
			}

			switch (syscall) {
			case MQ_OPEN: if(REPORT_IPC){ ipcManager.handleMq_open(eventData, syscall); } break;
			case MQ_TIMEDSEND: if(REPORT_IPC){ ipcManager.handleMq_timedsend(eventData, syscall); } break;
			case MQ_TIMEDRECEIVE: if(REPORT_IPC){ ipcManager.handleMq_timedreceive(eventData, syscall); } break;
			case MQ_UNLINK: if(REPORT_IPC){ ipcManager.handleMq_unlink(eventData, syscall); } break;
			case SHMGET: if(REPORT_IPC){ ipcManager.handleShmget(eventData, syscall); } break;
			case SHMAT: if(REPORT_IPC){ ipcManager.handleShmat(eventData, syscall); } break;
			case SHMDT: if(REPORT_IPC){ ipcManager.handleShmdt(eventData, syscall); } break;
			case SHMCTL: if(REPORT_IPC){ ipcManager.handleShmctl(eventData, syscall); } break;
			case MSGGET: if(REPORT_IPC){ ipcManager.handleMsgget(eventData, syscall); } break;
			case MSGSND: if(REPORT_IPC){ ipcManager.handleMsgsnd(eventData, syscall); } break;
			case MSGRCV: if(REPORT_IPC){ ipcManager.handleMsgrcv(eventData, syscall); } break;
			case MSGCTL: if(REPORT_IPC){ ipcManager.handleMsgctl(eventData, syscall); } break;
			case SETNS:
				if(HANDLE_NAMESPACES){
					processManager.handleSetns(eventData, syscall);
				}
				break;
			case UNSHARE:
				if(HANDLE_NAMESPACES){
					processManager.handleUnshare(eventData, syscall);
				}
				break;
			case PIVOT_ROOT:
				if(HANDLE_ROOTFS){
					handlePivotRoot(eventData, syscall);
				}
				break;
			case CHROOT:
				if(HANDLE_ROOTFS){
					handleChroot(eventData, syscall);
				}
				break;
			case CHDIR:
			case FCHDIR:
				if(HANDLE_CHDIR){
					handleChdir(eventData, syscall);
				}
			break;
			case LSEEK:
				if(USE_READ_WRITE){
					handleLseek(eventData, syscall);
				}
			break;
			case MADVISE:
				if(USE_MEMORY_SYSCALLS){
					handleMadvise(eventData, syscall);
				}
			break;
			case KILL:
				if(REPORT_KILL){
					handleKill(eventData, syscall);
				}
				break;
			case PTRACE:
				handlePtrace(eventData, syscall);
				break;
			case SOCKETPAIR:
				handleSocketPair(eventData, syscall);
				break;
			case TEE:
			case SPLICE:
				handleTeeSplice(eventData, syscall);
				break;
			case VMSPLICE:
				handleVmsplice(eventData, syscall);
				break;
			case INIT_MODULE:
			case FINIT_MODULE:
				handleInitModule(eventData, syscall);
				break;
			case FCNTL:
				handleFcntl(eventData, syscall);
				break;
			case EXIT:
			case EXIT_GROUP:
				handleExit(eventData, syscall);
				break;
			case WRITE: 
			case WRITEV:
			case PWRITE:
			case PWRITEV:
				handleIOEvent(syscall, eventData, false, eventData.get(AuditEventReader.EXIT));
				break;
			case SENDMSG:
			case SENDTO:
				if(!HANDLE_KM_RECORDS){
					handleIOEvent(syscall, eventData, false, eventData.get(AuditEventReader.EXIT));
				}
				break;
			case RECVFROM: 
			case RECVMSG:
				if(!HANDLE_KM_RECORDS){
					handleIOEvent(syscall, eventData, true, eventData.get(AuditEventReader.EXIT));
				}
				break;
			case READ: 
			case READV:
			case PREAD:
			case PREADV:
				handleIOEvent(syscall, eventData, true, eventData.get(AuditEventReader.EXIT));
				break;
			case MMAP:
				handleMmap(eventData, syscall);
				break;
			case MPROTECT:
				handleMprotect(eventData, syscall);
				break;
			case SYMLINK:
			case LINK:
			case SYMLINKAT:
			case LINKAT:
				handleLinkSymlink(eventData, syscall);
				break;
			case UNLINK:
			case UNLINKAT:
				handleUnlink(eventData, syscall);
				break;
			case VFORK:
			case FORK:
			case CLONE:
				processManager.handleForkVforkClone(eventData, syscall);
				break;
			case EXECVE:
				handleExecve(eventData, syscall);
				break;
			case OPEN:
			case OPENAT:
			case CREAT:
				handleOpen(eventData, syscall);
				break;
			case CLOSE:
				handleClose(eventData);
				break;
			case MKNOD:
			case MKNODAT:
				handleMknod(eventData, syscall);
				break;
			case DUP:
			case DUP2:
			case DUP3:
				handleDup(eventData, syscall);
				break;
			case SOCKET:
				if(!HANDLE_KM_RECORDS){
					handleSocket(eventData, syscall);
				}
				break;
			case BIND:
				if(!HANDLE_KM_RECORDS){
					handleBind(eventData, syscall);
				}
				break;
			case ACCEPT4:
			case ACCEPT:
				if(!HANDLE_KM_RECORDS){
					handleAccept(eventData, syscall);
				}
				break;
			case CONNECT:
				if(!HANDLE_KM_RECORDS){
					handleConnect(eventData, syscall);
				}
				break;
			case RENAME:
			case RENAMEAT:
				handleRename(eventData, syscall);
				break;
			case SETUID:
			case SETREUID:
			case SETRESUID:
			case SETFSUID:
			case SETGID:
			case SETREGID:
			case SETRESGID:
			case SETFSGID:
				handleSetuidAndSetgid(eventData, syscall);
				break; 
			case CHMOD:
			case FCHMOD:
			case FCHMODAT:
				handleChmod(eventData, syscall);
				break;
			case PIPE:
			case PIPE2:
				handlePipe(eventData, syscall);
				break;
			case TRUNCATE:
			case FTRUNCATE:
				handleTruncate(eventData, syscall);
				break;
			default: //SYSCALL.UNSUPPORTED
				//log(Level.INFO, "Unsupported syscall '"+syscallNum+"'", null, eventData.get("time"), eventId, syscall);
			}
		} catch (Exception e) {
			logger.log(Level.WARNING, "Error processing finish syscall event with eventid '"+eventId+"'", e);
		}
	}

	public final void handleIOEvent(SYSCALL syscall, Map<String, String> eventData, boolean isRead, final String bytesTransferred){
		String eventId = eventData.get(AuditEventReader.EVENT_ID);
		String time = eventData.get(AuditEventReader.TIME);
		String pid = eventData.get(AuditEventReader.PID);
		String saddr = eventData.get(AuditEventReader.SADDR);
		String fd = eventData.get(AuditEventReader.ARG0);
		String offset = null;
		ArtifactIdentifier artifactIdentifier = null;	
		
		if(!isNetlinkSaddr(saddr)){
			artifactIdentifier = getNetworkIdentifierFromFdAndOrSaddr(syscall, time, eventId, pid, fd, saddr);
			if(syscall == SYSCALL.PREAD || syscall == SYSCALL.PREADV
					|| syscall == SYSCALL.PWRITE || syscall == SYSCALL.PWRITEV){
				offset = eventData.get(AuditEventReader.ARG3);
			}
			
			putIO(eventData, time, eventId, syscall, pid, fd, artifactIdentifier, bytesTransferred, offset, isRead);
		}
	}
	
	private void handleMadvise(Map<String, String> eventData, SYSCALL syscall){
		String eventId = eventData.get(AuditEventReader.EVENT_ID);
		String time = eventData.get(AuditEventReader.TIME);
		String pid = eventData.get(AuditEventReader.PID);
		String address = new BigInteger(eventData.get(AuditEventReader.ARG0)).toString(16);
		String length = new BigInteger(eventData.get(AuditEventReader.ARG1)).toString(16);
		String adviceString = eventData.get(AuditEventReader.ARG2);

		Integer adviceInt = HelperFunctions.parseInt(adviceString, null);
		if(adviceInt == null){
			log(Level.WARNING, "Expected 3rd argument (a2) to be integer but is '"+adviceString+"'", 
					null, time, eventId, syscall);
		}else{
			String adviceAnnotation = madviseAdviceValues.get(adviceInt);
			if(adviceAnnotation == null){
				log(Level.WARNING, 
						"Expected 3rd argument (a2), which is '"+adviceString+"', to be one of: "
						+ getValueNameMapAsString(madviseAdviceValues), 
						null, time, eventId, syscall);
			}else{
				String tgid = processManager.getMemoryTgid(pid);
				
				ArtifactIdentifier memoryIdentifier = new MemoryIdentifier(tgid, address, length);
				Artifact memoryArtifact = putArtifactFromSyscall(eventData, memoryIdentifier);

				Process process = processManager.handleProcessFromSyscall(eventData);
				WasGeneratedBy edge = new WasGeneratedBy(memoryArtifact, process);
				edge.addAnnotation(OPMConstants.EDGE_ADVICE, adviceAnnotation);
				putEdge(edge, getOperation(syscall), time, eventId, AUDIT_SYSCALL_SOURCE);
			}
		}
	}
	
	private void handleLseek(Map<String, String> eventData, SYSCALL syscall){
		String eventId = eventData.get(AuditEventReader.EVENT_ID);
		String time = eventData.get(AuditEventReader.TIME);
		String pid = eventData.get(AuditEventReader.PID);
		String fd = eventData.get(AuditEventReader.ARG0);
		String offsetRequested = eventData.get(AuditEventReader.ARG1);
		String whenceString = eventData.get(AuditEventReader.ARG2);
		String offsetActual = eventData.get(AuditEventReader.EXIT);
		
		Integer whence = HelperFunctions.parseInt(whenceString, null);
		if(whence == null){
			log(Level.WARNING, "Expected 3rd argument (a2) to be integer but is '"+whenceString+"'", 
					null, time, eventId, syscall);
		}else{
			String whenceAnnotation = lseekWhenceValues.get(whence);
			if(whenceAnnotation == null){
				log(Level.WARNING, 
						"Expected 3rd argument (a2), which is '"+whenceString+"', to be one of: "
						+ getValueNameMapAsString(lseekWhenceValues), 
						null, time, eventId, syscall);
			}else{
				FileDescriptor fileDescriptor = processManager.getFd(pid, fd);
				if(fileDescriptor == null){
					fileDescriptor = addUnknownFd(pid, fd);
				}
				Process process = processManager.handleProcessFromSyscall(eventData);
				Artifact artifact = putArtifactFromSyscall(eventData, fileDescriptor.identifier);
				WasGeneratedBy wgb = new WasGeneratedBy(artifact, process);
				wgb.addAnnotation(OPMConstants.EDGE_OFFSET, offsetActual);
				wgb.addAnnotation(OPMConstants.EDGE_LSEEK_WHENCE, whenceAnnotation);
				putEdge(wgb, getOperation(syscall), time, eventId, AUDIT_SYSCALL_SOURCE);
			}
		}
	}
	
	private void handlePivotRoot(Map<String, String> eventData, SYSCALL syscall){
		// pivot_root() receives the following messages(s):
		// - SYSCALL
		// - PATH with NORMAL nametype
		// - CWD - different in different cases. Not handling those for now.
		// - EOE
		
		String time = eventData.get(AuditEventReader.TIME);
		String eventId = eventData.get(AuditEventReader.EVENT_ID);
		String pid = eventData.get(AuditEventReader.PID);
		String processCwd = processManager.getCwd(pid); // never null
		
		final PathRecord pathRecord = PathRecord.getFirstPathWithNametype(eventData, 
				AuditEventReader.NAMETYPE_NORMAL);
		
		if(pathRecord == null){
			log(Level.WARNING, "Missing PATH record", null, time, eventId, syscall);
		}else{
			String path = pathRecord.getPath();
			String newRoot = LinuxPathResolver.constructAbsolutePath(path, processCwd, pid);
			newRoot = LinuxPathResolver.joinPaths(newRoot, processManager.getRoot(pid), pid);
			if(newRoot == null){
				log(Level.WARNING, "Failed to construct path", null, time, eventId, syscall);
			}else{
				processManager.pivot_root(pid, newRoot, null);
			}
		}
	}
	
	private void handleChroot(Map<String, String> eventData, SYSCALL syscall){
		// chroot() receives the following messages(s):
		// - SYSCALL
		// - PATH with NORMAL nametype
		// - CWD - different in different cases. Not handling those for now.
		// - EOE
		
		String time = eventData.get(AuditEventReader.TIME);
		String eventId = eventData.get(AuditEventReader.EVENT_ID);
		String pid = eventData.get(AuditEventReader.PID);
		String processCwd = processManager.getCwd(pid); // never null
		
		final PathRecord pathRecord = PathRecord.getFirstPathWithNametype(eventData, 
				AuditEventReader.NAMETYPE_NORMAL);
		
		if(pathRecord == null){
			log(Level.WARNING, "Missing PATH record", null, time, eventId, syscall);
		}else{
			String path = pathRecord.getPath();
			String newRoot = LinuxPathResolver.constructAbsolutePath(path, processCwd, pid);
			newRoot = LinuxPathResolver.joinPaths(newRoot, processManager.getRoot(pid), pid);
			if(newRoot == null){
				log(Level.WARNING, "Failed to construct path", null, time, eventId, syscall);
			}else{
				processManager.chroot(pid, newRoot);
			}
		}
	}
	
	private void handleChdir(Map<String, String> eventData, SYSCALL syscall){
		// chdir() receives the following messages(s):
		// - SYSCALL
		// - PATH with NORMAL nametype
		// - CWD (pre-syscall value) (i.e. not taken at exit of syscall)
		// - EOE
		
		// fchdir() receives the following messages(s):
		// - SYSCALL
		// - EOE
		
		String time = eventData.get(AuditEventReader.TIME);
		String eventId = eventData.get(AuditEventReader.EVENT_ID);
		String pid = eventData.get(AuditEventReader.PID);
		
		if(syscall == SYSCALL.CHDIR){
			PathRecord normalPathRecord = PathRecord.getFirstPathWithNametype(eventData, AuditEventReader.NAMETYPE_NORMAL);
			if(normalPathRecord == null){
				log(Level.WARNING, "Missing PATH record", null, time, eventId, syscall);
			}else{
				String path = normalPathRecord.getPath();
				if(LinuxPathResolver.isAbsolutePath(path)){
					// update the cwd to this
					// update the cwd root to the current process root
					processManager.absoluteChdir(pid, path);
				}else{
					// relative path
					// Need to resolve according to CWD
					String currentProcessCwd = processManager.getCwd(pid);
					if(currentProcessCwd == null){
						String auditRecordCwd = eventData.get(AuditEventReader.CWD);
						if(auditRecordCwd == null){
							log(Level.WARNING, "Missing CWD record as well as process state", null, time, eventId, syscall);
						}else{
							String finalPath = LinuxPathResolver.constructAbsolutePath(path, auditRecordCwd, pid);
							// update the cwd and not the cwd root
							processManager.relativeChdir(pid, finalPath);
						}
					}else{
						String finalPath = LinuxPathResolver.constructAbsolutePath(path, currentProcessCwd, pid);
						// update cwd and not the cwd root
						processManager.relativeChdir(pid, finalPath);
					}
				}
			}
		}else if(syscall == SYSCALL.FCHDIR){
			String fd = eventData.get(AuditEventReader.ARG0);
			FileDescriptor fileDescriptor = processManager.getFd(pid, fd);
			if(fileDescriptor != null){
				if(fileDescriptor.identifier instanceof DirectoryIdentifier){
					DirectoryIdentifier directoryIdentifier = (DirectoryIdentifier)fileDescriptor.identifier;
					String newCwdPath = directoryIdentifier.path;
					String newCwdRoot = directoryIdentifier.rootFSPath;
					// root path from dir
					processManager.fdChdir(pid, newCwdPath, newCwdRoot);
				}else{
					log(Level.WARNING, "Unexpected FD type to change currrent working directory to. Expected directory. Found: "
							+ fileDescriptor.identifier.getClass(), null, time, eventId, syscall);
				}
			}else{
				log(Level.WARNING, "Missing FD to change current working directory to", null, time, eventId, syscall);
			}	
		}else{
			log(Level.INFO, "Unexpected syscall '"+syscall+"' in CHDIR handler", null, time, eventId, syscall);
		}
	}

	private PathIdentifier resolvePath_At(PathRecord pathRecord,  
			String atSyscallFdKey,
			Map<String, String> eventData, SYSCALL syscall){
		return LinuxPathResolver.resolvePath(
				pathRecord, eventData.get(AuditEventReader.CWD), eventData.get(AuditEventReader.PID), 
				atSyscallFdKey, eventData.get(atSyscallFdKey), true,
				eventData.get(AuditEventReader.TIME), eventData.get(AuditEventReader.EVENT_ID), syscall, 
				this, processManager, artifactManager, HANDLE_CHDIR);
	}
	
	public final PathIdentifier resolvePath(PathRecord pathRecord,
			Map<String, String> eventData, SYSCALL syscall){
		return LinuxPathResolver.resolvePath(
				pathRecord, eventData.get(AuditEventReader.CWD), eventData.get(AuditEventReader.PID), 
				null, null, false,
				eventData.get(AuditEventReader.TIME), eventData.get(AuditEventReader.EVENT_ID), syscall, 
				this, processManager, artifactManager, HANDLE_CHDIR);
	}

	public final void handleUnlink(Map<String, String> eventData, SYSCALL syscall){
		// unlink() and unlinkat() receive the following messages(s):
		// - SYSCALL
		// - PATH with PARENT nametype
		// - PATH with DELETE nametype relative to CWD
		// - CWD
		// - EOE

		if(CONTROL){
			String time = eventData.get(AuditEventReader.TIME);
			String eventId = eventData.get(AuditEventReader.EVENT_ID);
			
			String pathAuditNametype = AuditEventReader.NAMETYPE_DELETE;
			PathRecord pathRecord = PathRecord.getFirstPathWithNametype(eventData, pathAuditNametype);
			
			PathIdentifier pathIdentifier = null;
			if(syscall == SYSCALL.UNLINK || syscall == SYSCALL.MQ_UNLINK){
				pathIdentifier = resolvePath(pathRecord, eventData, syscall);
				if(syscall == SYSCALL.MQ_UNLINK){
					pathIdentifier = new PosixMessageQueue(pathIdentifier.path, pathIdentifier.rootFSPath);
				}
			}else if(syscall == SYSCALL.UNLINKAT){
				pathIdentifier = resolvePath_At(pathRecord, AuditEventReader.ARG0, eventData, syscall);
			}else{
				log(Level.INFO, "Unexpected syscall '"+syscall+"' in UNLINK handler", null, time, eventId, syscall);
			}

			if(pathIdentifier != null){
				artifactManager.artifactPermissioned(pathIdentifier, pathRecord.getPermissions());
				
				Process process = processManager.handleProcessFromSyscall(eventData);
				Artifact artifact = putArtifactFromSyscall(eventData, pathIdentifier);
				WasGeneratedBy deletedEdge = new WasGeneratedBy(artifact, process);
				putEdge(deletedEdge, getOperation(syscall), time, eventId, AUDIT_SYSCALL_SOURCE);
			}
		}
	}

	private FileDescriptor addUnknownFd(String pid, String fd){
		String fdTgid = processManager.getFdTgid(pid);
		UnknownIdentifier unknown = new UnknownIdentifier(fdTgid, fd);
		FileDescriptor fileDescriptor = new FileDescriptor(unknown, null);
		artifactManager.artifactCreated(unknown);
		processManager.setFd(pid, fd, fileDescriptor);
		return fileDescriptor;
	}
	
	private void handleFcntl(Map<String, String> eventData, SYSCALL syscall){
		// fcntl() receives the following message(s):
		// - SYSCALL
		// - EOE
		 
		String exit = eventData.get(AuditEventReader.EXIT);
		if("-1".equals(exit)){ // Failure check
			return;
		}
		
		String pid = eventData.get(AuditEventReader.PID);
		String fd = eventData.get(AuditEventReader.ARG0);
		String cmdString = eventData.get(AuditEventReader.ARG1);
		String flagsString = eventData.get(AuditEventReader.ARG2);
		
		int cmd = HelperFunctions.parseInt(cmdString, -1);
		int flags = HelperFunctions.parseInt(flagsString, -1);
		
		if(cmd == F_DUPFD || cmd == F_DUPFD_CLOEXEC){
			// In eventData, there should be a pid, a0 should be fd, and exit should be the new fd 
			handleDup(eventData, syscall);
		}else if(cmd == F_SETFL){
			if((flags & O_APPEND) == O_APPEND){
				FileDescriptor fileDescriptor = processManager.getFd(pid, fd);
				if(fileDescriptor == null){
					fileDescriptor = addUnknownFd(pid, fd);
				}
				// Made file descriptor 'appendable', so set open for read to false so 
				// that the edge on close is a WGB edge and not a Used edge
				if(fileDescriptor.getWasOpenedForRead() != null){
					fileDescriptor.setWasOpenedForRead(false);
				}
			}
		}
	}
	
	private void handleExit(Map<String, String> eventData, SYSCALL syscall){
		// exit(), and exit_group() receives the following message(s):
		// - SYSCALL
		// - EOE
		processManager.handleExit(eventData, syscall, CONTROL);
	}

	private void handleMmap(Map<String, String> eventData, SYSCALL syscall){
		// mmap() receive the following message(s):
		// - MMAP
		// - SYSCALL
		// - EOE

		if(!USE_MEMORY_SYSCALLS){
			return;
		}

		String eventId = eventData.get(AuditEventReader.EVENT_ID);
		String pid = eventData.get(AuditEventReader.PID);
		String time = eventData.get(AuditEventReader.TIME);
		String address = new BigInteger(eventData.get(AuditEventReader.EXIT)).toString(16); //convert to hexadecimal
		String length = new BigInteger(eventData.get(AuditEventReader.ARG1)).toString(16); //convert to hexadecimal
		String protection = new BigInteger(eventData.get(AuditEventReader.ARG2)).toString(16); //convert to hexadecimal
		
		int flags = HelperFunctions.parseInt(eventData.get(AuditEventReader.ARG3), 0);
		
		// Put Process, Memory artifact and WasGeneratedBy edge always but return if flag
		// is MAP_ANONYMOUS
		
		if(((flags & MAP_ANONYMOUS) == MAP_ANONYMOUS) && !ANONYMOUS_MMAP){
			return;
		}
		
		Process process = processManager.handleProcessFromSyscall(eventData);		
		String tgid = processManager.getMemoryTgid(pid);
		ArtifactIdentifier memoryArtifactIdentifier = new MemoryIdentifier(tgid, address, length);
		artifactManager.artifactVersioned(memoryArtifactIdentifier);
		Artifact memoryArtifact = putArtifactFromSyscall(eventData, memoryArtifactIdentifier);
		WasGeneratedBy wgbEdge = new WasGeneratedBy(memoryArtifact, process);
		wgbEdge.addAnnotation(OPMConstants.EDGE_PROTECTION, protection);
		putEdge(wgbEdge, getOperation(syscall, SYSCALL.WRITE), time, eventId, AUDIT_SYSCALL_SOURCE);		
		
		if((flags & MAP_ANONYMOUS) == MAP_ANONYMOUS){
			return;
		}else{
		
			String fd = eventData.get(AuditEventReader.FD);
	
			if(fd == null){
				log(Level.INFO, "FD record missing", null, time, eventId, syscall);
				return;
			}
	
			FileDescriptor fileDescriptor = processManager.getFd(pid, fd);
	
			if(fileDescriptor == null){
				fileDescriptor = addUnknownFd(pid, fd);
			}
	
			Artifact artifact = putArtifactFromSyscall(eventData, fileDescriptor.identifier);
	
			Used usedEdge = new Used(process, artifact);
			putEdge(usedEdge, getOperation(syscall, SYSCALL.READ), time, eventId, AUDIT_SYSCALL_SOURCE);
	
			WasDerivedFrom wdfEdge = new WasDerivedFrom(memoryArtifact, artifact);
			wdfEdge.addAnnotation(OPMConstants.EDGE_PID, pid);
			putEdge(wdfEdge, getOperation(syscall), time, eventId, AUDIT_SYSCALL_SOURCE);
		}

	}

	private void handleMprotect(Map<String, String> eventData, SYSCALL syscall){
		// mprotect() receive the following message(s):
		// - SYSCALL
		// - EOE

		if(!USE_MEMORY_SYSCALLS){
			return;
		}

		String eventId = eventData.get(AuditEventReader.EVENT_ID);
		String pid = eventData.get(AuditEventReader.PID);
		String time = eventData.get(AuditEventReader.TIME);
		String address = new BigInteger(eventData.get(AuditEventReader.ARG0)).toString(16);
		String length = new BigInteger(eventData.get(AuditEventReader.ARG1)).toString(16);
		String protection = new BigInteger(eventData.get(AuditEventReader.ARG2)).toString(16);

		String tgid = processManager.getMemoryTgid(pid);
		
		ArtifactIdentifier memoryIdentifier = new MemoryIdentifier(tgid, address, length);
		artifactManager.artifactVersioned(memoryIdentifier);
		Artifact memoryArtifact = putArtifactFromSyscall(eventData, memoryIdentifier);

		Process process = processManager.handleProcessFromSyscall(eventData);
		WasGeneratedBy edge = new WasGeneratedBy(memoryArtifact, process);
		edge.addAnnotation(OPMConstants.EDGE_PROTECTION, protection);
		putEdge(edge, getOperation(syscall), time, eventId, AUDIT_SYSCALL_SOURCE);
	}

	private void handleExecve(Map<String, String> eventData, SYSCALL syscall) {
		// execve() receives the following message(s):
		// - SYSCALL
		// - EXECVE
		// - BPRM_FCAPS (ignored)
		// - CWD
		// - PATH
		// - PATH
		// - EOE

		String time = eventData.get(AuditEventReader.TIME);
		String eventId = eventData.get(AuditEventReader.EVENT_ID);
		String pid = eventData.get(AuditEventReader.PID);

		Process process = processManager.handleExecve(eventData, syscall);

		List<PathRecord> loadPathRecords = PathRecord.getPathsWithNametype(eventData, AuditEventReader.NAMETYPE_NORMAL);
		for(PathRecord loadPathRecord : loadPathRecords){
			if(loadPathRecord != null){
				PathIdentifier pathIdentifier = resolvePath(loadPathRecord, eventData, syscall);
				if(pathIdentifier != null){
					artifactManager.artifactPermissioned(pathIdentifier, loadPathRecord.getPermissions());
					
					Artifact usedArtifact = putArtifactFromSyscall(eventData, pathIdentifier);
					Used usedEdge = new Used(process, usedArtifact);
					putEdge(usedEdge, getOperation(SYSCALL.LOAD), time, eventId, AUDIT_SYSCALL_SOURCE);
				}
			}
		}

		String processName = process.getAnnotation(OPMConstants.PROCESS_NAME);
		if(namesOfProcessesToIgnoreFromConfig.contains(processName)){
			log(Level.INFO, "'"+processName+"' (pid="+pid+") process seen in execve and present in list of processes to ignore", 
					null, time, eventId, syscall);
		}
	}

	public final boolean openFlagsHasCreateFlag(final int flagsInt){
		return (flagsInt & O_CREAT) == O_CREAT;
	}
	
	public final boolean openFlagsHasWriteRelatedFlags(final int flagsInt){
		return ((flagsInt & O_WRONLY) == O_WRONLY || 
				(flagsInt & O_RDWR) == O_RDWR ||
				 (flagsInt & O_APPEND) == O_APPEND || 
				 (flagsInt & O_TRUNC) == O_TRUNC);
	}
	
	public final boolean openFlagsHasReadOnlyFlag(final int flagsInt){
		return (flagsInt & O_RDONLY) == O_RDONLY;
	}
	
	public final String createOpenFlagsAnnotationValue(final int flagsInt){
		String flagsAnnotation = "";
		
		flagsAnnotation += ((flagsInt & O_WRONLY) == O_WRONLY) ? "O_WRONLY|" : "";
		flagsAnnotation += ((flagsInt & O_RDWR) == O_RDWR) ? "O_RDWR|" : "";
		// if neither write only nor read write then must be read only
		if(((flagsInt & O_WRONLY) != O_WRONLY) && 
				((flagsInt & O_RDWR) != O_RDWR)){ 
			// O_RDONLY is 0, so always true
			flagsAnnotation += ((flagsInt & O_RDONLY) == O_RDONLY) ? "O_RDONLY|" : "";
		}
		
		flagsAnnotation += ((flagsInt & O_APPEND) == O_APPEND) ? "O_APPEND|" : "";
		flagsAnnotation += ((flagsInt & O_TRUNC) == O_TRUNC) ? "O_TRUNC|" : "";
		flagsAnnotation += ((flagsInt & O_CREAT) == O_CREAT) ? "O_CREAT|" : "";
		
		if(!flagsAnnotation.isEmpty()){
			flagsAnnotation = flagsAnnotation.substring(0, flagsAnnotation.length() - 1);
		}
		return flagsAnnotation;
	}
	
	public final void handleOpen(Map<String, String> eventData, SYSCALL syscall){
		// open() receives the following message(s):
		// - SYSCALL
		// - CWD
		// - PATH with nametype CREATE (file operated on) or NORMAL (file operated on) or PARENT (parent of file operated on) or DELETE (file operated on) or UNKNOWN (only when syscall fails)
		// - PATH with nametype CREATE or NORMAL or PARENT or DELETE or UNKNOWN
		// - EOE
		
		String eventTime = eventData.get(AuditEventReader.TIME);
		String eventId = eventData.get(AuditEventReader.EVENT_ID);
		String pid = eventData.get(AuditEventReader.PID);
		String fd = eventData.get(AuditEventReader.EXIT);
		String modeString = null;
		String flagsString = null;

		final PathRecord pathRecord = PathRecord.getPathWithCreateOrNormalNametype(eventData);
		
		if(pathRecord == null){
			log(Level.INFO, "Missing PATH record with NORMAL/CREATE nametype", null, eventTime, eventId, syscall);
			return;
		}
		
		PathIdentifier artifactIdentifier = null;

		if(syscall == SYSCALL.OPEN || syscall == SYSCALL.MQ_OPEN){
			artifactIdentifier = resolvePath(pathRecord, eventData, syscall);
			if(syscall == SYSCALL.MQ_OPEN){
				artifactIdentifier = new PosixMessageQueue(artifactIdentifier.path, artifactIdentifier.rootFSPath);
			}
			flagsString = eventData.get(AuditEventReader.ARG1);
			modeString = eventData.get(AuditEventReader.ARG2);
		}else if(syscall == SYSCALL.OPENAT){
			artifactIdentifier = resolvePath_At(pathRecord, AuditEventReader.ARG0, eventData, syscall);
			flagsString = eventData.get(AuditEventReader.ARG2);
			modeString = eventData.get(AuditEventReader.ARG3);
		}else if(syscall == SYSCALL.CREAT || syscall == SYSCALL.CREATE){
			syscall = SYSCALL.CREATE;
			artifactIdentifier = resolvePath(pathRecord, eventData, syscall);
			int flagsInt = O_CREAT|O_WRONLY|O_TRUNC;
			flagsString = String.valueOf(flagsInt);
			modeString = eventData.get(AuditEventReader.ARG1);
		}else{
			log(Level.INFO, "Unexpected syscall in OPEN handler", null, eventTime, eventId, syscall);
			return;
		}
		
		if(artifactIdentifier != null){
			int flagsInt = HelperFunctions.parseInt(flagsString, 0);
			
			final boolean isCreate = openFlagsHasCreateFlag(flagsInt);
			
			String flagsAnnotation = createOpenFlagsAnnotationValue(flagsInt);
			
			String modeAnnotation = null;
			
			if(isCreate){
				syscall = SYSCALL.CREATE;
				artifactManager.artifactCreated(artifactIdentifier);
				modeAnnotation = Long.toOctalString(HelperFunctions.parseInt(modeString, 0));
			}
			
			boolean openedForRead;
			AbstractEdge edge = null;
			Process process = processManager.handleProcessFromSyscall(eventData);
			
			if(openFlagsHasWriteRelatedFlags(flagsInt)){
				if(!isCreate){
					// If artifact not created
					artifactManager.artifactVersioned(artifactIdentifier);
				}
				artifactManager.artifactPermissioned(artifactIdentifier, pathRecord.getPermissions());
				Artifact vertex = putArtifactFromSyscall(eventData, artifactIdentifier);
				edge = new WasGeneratedBy(vertex, process);
				openedForRead = false;
			}else if(openFlagsHasReadOnlyFlag(flagsInt)){
				artifactManager.artifactPermissioned(artifactIdentifier, pathRecord.getPermissions());
				if(isCreate){
					Artifact vertex = putArtifactFromSyscall(eventData, artifactIdentifier);
					edge = new WasGeneratedBy(vertex, process);
				}else{
					Artifact vertex = putArtifactFromSyscall(eventData, artifactIdentifier);
					edge = new Used(process, vertex);
				}
				openedForRead = true;
			}else{
				log(Level.INFO, "Unhandled value of FLAGS argument '"+flagsString+"'", null, eventTime, eventId, syscall);
				return;
			}
			
			if(edge != null){
				if(modeAnnotation != null){
					edge.addAnnotation(OPMConstants.EDGE_MODE, modeAnnotation);
				}
				if(!flagsAnnotation.isEmpty()){
					edge.addAnnotation(OPMConstants.EDGE_FLAGS, flagsAnnotation);
				}
				//everything happened successfully. add it to descriptors
				FileDescriptor fileDescriptor = new FileDescriptor(artifactIdentifier, openedForRead);
				processManager.setFd(pid, fd, fileDescriptor);

				putEdge(edge, getOperation(syscall), eventTime, eventId, AUDIT_SYSCALL_SOURCE);
			}
		}
	}

	private void handleClose(Map<String, String> eventData) {
		// close() receives the following message(s):
		// - SYSCALL
		// - EOE
		String pid = eventData.get(AuditEventReader.PID);
		String fd = String.valueOf(HelperFunctions.parseLong(eventData.get(AuditEventReader.ARG0), -1L));
		FileDescriptor closedFileDescriptor = processManager.removeFd(pid, fd);
		
		if(CONTROL){
			SYSCALL syscall = SYSCALL.CLOSE;
			String time = eventData.get(AuditEventReader.TIME);
			String eventId = eventData.get(AuditEventReader.EVENT_ID);
			if(closedFileDescriptor != null){
				Process process = processManager.handleProcessFromSyscall(eventData);
				AbstractEdge edge = null;
				Boolean wasOpenedForRead = closedFileDescriptor.getWasOpenedForRead();
				if(wasOpenedForRead == null){
					// Not drawing an edge because didn't seen an open or was a 'bound' fd
				}else{
					Artifact artifact = putArtifactFromSyscall(eventData, closedFileDescriptor.identifier);
					if(wasOpenedForRead){
						edge = new Used(process, artifact);
					}else{
						edge = new WasGeneratedBy(artifact, process);
					}
				}
				if(edge != null){
					putEdge(edge, getOperation(syscall), time, eventId, AUDIT_SYSCALL_SOURCE);
				}
				//after everything done increment epoch is udp socket
				if(isUdp(closedFileDescriptor.identifier)){
					artifactManager.artifactCreated(closedFileDescriptor.identifier);
				}
			}else{
				log(Level.INFO, "No FD with number '"+fd+"' for pid '"+pid+"'", null, time, eventId, syscall);
			}
		}

		//there is an option to either handle epochs 1) when artifact opened/created or 2) when artifacts deleted/closed.
		//handling epoch at opened/created in all cases
	}

	private void handleTruncate(Map<String, String> eventData, SYSCALL syscall) {
		// write() receives the following message(s):
		// - SYSCALL
		// - EOE
		String time = eventData.get(AuditEventReader.TIME);
		String eventId = eventData.get(AuditEventReader.EVENT_ID);
		String pid = eventData.get(AuditEventReader.PID);
		String size = eventData.get(AuditEventReader.ARG1);

		ArtifactIdentifier artifactIdentifier = null;
		
		if(syscall == SYSCALL.TRUNCATE){
			PathRecord pathRecord = PathRecord.getFirstPathWithNametype(eventData, AuditEventReader.NAMETYPE_NORMAL);
			artifactIdentifier = resolvePath(pathRecord, eventData, syscall);
			if(artifactIdentifier != null){
				artifactManager.artifactVersioned(artifactIdentifier);
				artifactManager.artifactPermissioned(artifactIdentifier, pathRecord.getPermissions());
			}
		}else if(syscall == SYSCALL.FTRUNCATE){
			String fd = eventData.get(AuditEventReader.ARG0);
			FileDescriptor fileDescriptor = processManager.getFd(pid, fd);
			fileDescriptor = fileDescriptor == null ? addUnknownFd(pid, fd) : fileDescriptor;
			artifactIdentifier = fileDescriptor.identifier;
			artifactManager.artifactVersioned(artifactIdentifier);
		}

		if(artifactIdentifier != null){
			Process process = processManager.handleProcessFromSyscall(eventData);
			Artifact vertex = putArtifactFromSyscall(eventData, artifactIdentifier);
			WasGeneratedBy wgb = new WasGeneratedBy(vertex, process);
			if(size != null){
				wgb.addAnnotation(OPMConstants.EDGE_SIZE, size);
			}
			putEdge(wgb, getOperation(syscall), time, eventId, AUDIT_SYSCALL_SOURCE);
		}else{
			log(Level.INFO, "Failed to find artifact identifier from the event data", null, time, eventId, syscall);
		}
	}

	private void handleDup(Map<String, String> eventData, SYSCALL syscall) {
		// dup(), dup2(), and dup3() receive the following message(s):
		// - SYSCALL
		// - EOE
		String pid = eventData.get(AuditEventReader.PID);

		String fd = eventData.get(AuditEventReader.ARG0);
		String newFD = eventData.get(AuditEventReader.EXIT); //new fd returned in all: dup, dup2, dup3

		if(!fd.equals(newFD)){ //if both fds same then it succeeds in case of dup2 and it does nothing so do nothing here too
			FileDescriptor fileDescriptor = processManager.getFd(pid, fd);
			if(fileDescriptor == null){
				fileDescriptor = addUnknownFd(pid, fd);
			}
			processManager.setFd(pid, newFD, fileDescriptor);
		}
	}
	
	private void handleVmsplice(Map<String, String> eventData, SYSCALL syscall){
		// vmsplice() receives the following messages:
		// - SYSCALL
		// - EOE
		String time = eventData.get(AuditEventReader.TIME);
		String eventId = eventData.get(AuditEventReader.EVENT_ID);
		String pid = eventData.get(AuditEventReader.PID);
		
		String fdOut = eventData.get(AuditEventReader.ARG0);
		String bytes = eventData.get(AuditEventReader.EXIT);
		
		if(!"0".equals(bytes)){	
			FileDescriptor fdOutDescriptor = processManager.getFd(pid, fdOut);
			fdOutDescriptor = fdOutDescriptor == null ? addUnknownFd(pid, fdOut) : fdOutDescriptor;
			
			Process process = processManager.handleProcessFromSyscall(eventData);
			artifactManager.artifactVersioned(fdOutDescriptor.identifier);
			Artifact fdOutArtifact = putArtifactFromSyscall(eventData, fdOutDescriptor.identifier);
	
			WasGeneratedBy processToWrittenArtifact = new WasGeneratedBy(fdOutArtifact, process);
			processToWrittenArtifact.addAnnotation(OPMConstants.EDGE_SIZE, bytes);
			putEdge(processToWrittenArtifact, getOperation(syscall), time, eventId, AUDIT_SYSCALL_SOURCE);
		}
	}
	
	private void putTeeSplice(Map<String, String> eventData, SYSCALL syscall,
			String time, String eventId, String fdIn, String fdOut, String pid, String bytes){
		FileDescriptor fdInDescriptor = processManager.getFd(pid, fdIn);
		FileDescriptor fdOutDescriptor = processManager.getFd(pid, fdOut);

		// Use unknown if missing fds
		fdInDescriptor = fdInDescriptor == null ? addUnknownFd(pid, fdIn) : fdInDescriptor;
		fdOutDescriptor = fdOutDescriptor == null ? addUnknownFd(pid, fdOut) : fdOutDescriptor;

		Process process = processManager.handleProcessFromSyscall(eventData);
		Artifact fdInArtifact = putArtifactFromSyscall(eventData, fdInDescriptor.identifier);
		artifactManager.artifactVersioned(fdOutDescriptor.identifier);
		Artifact fdOutArtifact = putArtifactFromSyscall(eventData, fdOutDescriptor.identifier);

		Used processToReadArtifact = new Used(process, fdInArtifact);
		processToReadArtifact.addAnnotation(OPMConstants.EDGE_SIZE, bytes);
		putEdge(processToReadArtifact, getOperation(syscall, SYSCALL.READ), time, eventId, AUDIT_SYSCALL_SOURCE);
		
		WasGeneratedBy processToWrittenArtifact = new WasGeneratedBy(fdOutArtifact, process);
		processToWrittenArtifact.addAnnotation(OPMConstants.EDGE_SIZE, bytes);
		putEdge(processToWrittenArtifact, getOperation(syscall, SYSCALL.WRITE), time, eventId, AUDIT_SYSCALL_SOURCE);
		
		WasDerivedFrom writtenToReadArtifact = new WasDerivedFrom(fdOutArtifact, fdInArtifact);
		writtenToReadArtifact.addAnnotation(OPMConstants.EDGE_PID, pid);
		putEdge(writtenToReadArtifact, getOperation(syscall), time, eventId, AUDIT_SYSCALL_SOURCE);
	}
	
	private void handleTeeSplice(Map<String, String> eventData, SYSCALL syscall){
		// tee(), and splice() receive the following messages:
		// - SYSCALL
		// - EOE
		String time = eventData.get(AuditEventReader.TIME);
		String eventId = eventData.get(AuditEventReader.EVENT_ID);
		String pid = eventData.get(AuditEventReader.PID);

		String fdIn = eventData.get(AuditEventReader.ARG0), fdOut = null;
		String bytes = eventData.get(AuditEventReader.EXIT);
		
		if(syscall == SYSCALL.TEE){
			fdOut = eventData.get(AuditEventReader.ARG1);
		}else if(syscall == SYSCALL.SPLICE){
			fdOut = eventData.get(AuditEventReader.ARG2);
		}else{
			log(Level.WARNING, "Unexpected syscall: " + syscall, null, time, eventId, syscall);
			return;
		}
		
		// If fd out set and bytes transferred is not zero
		if(fdOut != null && !"0".equals(bytes)){
			putTeeSplice(eventData, syscall, time, eventId, fdIn, fdOut, pid, bytes);
		}
	}

	private void handleInitModule(Map<String, String> eventData, SYSCALL syscall){
		// init_module(), and finit_module receive the following messages:
		// - SYSCALL
		// - PATH [OPTIONAL] why? not the path of the kernel module
		// - EOE
		String time = eventData.get(AuditEventReader.TIME);
		String eventId = eventData.get(AuditEventReader.EVENT_ID);
		String pid = eventData.get(AuditEventReader.PID);
		
		ArtifactIdentifier moduleIdentifier = null;
		if(syscall == SYSCALL.INIT_MODULE){
			String memoryAddress = new BigInteger(eventData.get(AuditEventReader.ARG0)).toString(16); //convert to hexadecimal
			String memorySize = new BigInteger(eventData.get(AuditEventReader.ARG1)).toString(16); //convert to hexadecimal
			String tgid = processManager.getMemoryTgid(pid);
			moduleIdentifier = new MemoryIdentifier(tgid, memoryAddress, memorySize);
		}else if(syscall == SYSCALL.FINIT_MODULE){
			String fd = eventData.get(AuditEventReader.ARG0);
			FileDescriptor fileDescriptor = processManager.getFd(pid, fd);
			fileDescriptor = fileDescriptor == null ? addUnknownFd(pid, fd) : fileDescriptor;
			moduleIdentifier = fileDescriptor.identifier;
		}else{
			log(Level.WARNING, "Unexpected syscall in (f)init_module handler", null, time, eventId, syscall);
		}
		
		if(moduleIdentifier != null){
			Process process = processManager.handleProcessFromSyscall(eventData);
			Artifact module = putArtifactFromSyscall(eventData, moduleIdentifier);
			Used loadedModule = new Used(process, module);
			putEdge(loadedModule, getOperation(syscall), time, eventId, AUDIT_SYSCALL_SOURCE);
		}
	}
	
	private void handleSetuidAndSetgid(Map<String, String> eventData, SYSCALL syscall){
		// setuid(), setreuid(), setresuid(), setfsuid(), 
		// setgid(), setregid(), setresgid(), and setfsgid() receive the following message(s):
		// - SYSCALL
		// - EOE
		
		processManager.handleSetuidSetgid(eventData, syscall);
	}

	private void handleRename(Map<String, String> eventData, SYSCALL syscall) {
		// rename(), renameat(), and renameat2() receive the following message(s):
		// - SYSCALL
		// - CWD
		// - PATH 0
		// - PATH 1
		// - PATH 2 with nametype DELETE
		// - PATH 3 with nametype DELETE or CREATE
		// - [OPTIONAL] PATH 4 with nametype CREATE
		// - EOE
		// Resolving paths relative to CWD if not absolute

		String time = eventData.get(AuditEventReader.TIME);
		String eventId = eventData.get(AuditEventReader.EVENT_ID);
		
		PathRecord oldPathRecord = PathRecord.getPathWithItemNumber(eventData, 2);
		PathRecord newPathRecord = PathRecord.getPathWithItemNumber(eventData, 4);
		//if file renamed to already existed then path4 else path3. Both are same so just getting whichever exists
		newPathRecord = newPathRecord == null ? PathRecord.getPathWithItemNumber(eventData, 3) : newPathRecord;
		
		if(oldPathRecord == null){
			log(Level.WARNING, "Missing source PATH record", null, time, eventId, syscall);
			return;
		}
		
		if(newPathRecord == null){
			log(Level.WARNING, "Missing destination PATH record", null, time, eventId, syscall);
			return;
		}
		
		ArtifactIdentifier oldArtifactIdentifier = null;
		ArtifactIdentifier newArtifactIdentifier = null;
				
		if(syscall == SYSCALL.RENAME){
			oldArtifactIdentifier = resolvePath(oldPathRecord, eventData, syscall);
			newArtifactIdentifier = resolvePath(newPathRecord, eventData, syscall);
		}else if(syscall == SYSCALL.RENAMEAT){
			oldArtifactIdentifier = resolvePath_At(oldPathRecord, AuditEventReader.ARG0, eventData, syscall);
			newArtifactIdentifier = resolvePath_At(newPathRecord, AuditEventReader.ARG2, eventData, syscall);     	
		}else{
			log(Level.WARNING, "Unexpected syscall '"+syscall+"' in RENAME handler", null, time, eventId, syscall);
			return;
		}

		if(oldArtifactIdentifier == null || newArtifactIdentifier == null){
			return;
		}

		handleSpecialSyscalls(eventData, syscall, 
				oldPathRecord, oldArtifactIdentifier, 
				newPathRecord, newArtifactIdentifier);
	}
	
	private void handleMknod(Map<String, String> eventData, SYSCALL syscall){
		//mknod() receives the following message(s):
		// - SYSCALL
		// - CWD
		// - PATH of the parent with nametype=PARENT
		// - PATH of the created file with nametype=CREATE
		// - EOE

		String time = eventData.get(AuditEventReader.TIME);
		String eventId = eventData.get(AuditEventReader.EVENT_ID);

		PathRecord pathRecord = PathRecord.getFirstPathWithNametype(eventData, AuditEventReader.NAMETYPE_CREATE);
		
		ArtifactIdentifier artifactIdentifier = null;
		
		if(syscall == SYSCALL.MKNOD){
			artifactIdentifier = resolvePath(pathRecord, eventData, syscall);
		}else if(syscall == SYSCALL.MKNODAT){
			artifactIdentifier = resolvePath_At(pathRecord, AuditEventReader.ARG0, eventData, syscall);
		}else{
			log(Level.WARNING, "Unexpected syscall '"+syscall+"' in MKNOD handler", null, time, eventId, syscall);
			return;
		}
		
		if(artifactIdentifier != null){
			artifactManager.artifactCreated(artifactIdentifier);
		}
	}

	private void handleLinkSymlink(Map<String, String> eventData, SYSCALL syscall) {
		// link(), symlink(), linkat(), and symlinkat() receive the following message(s):
		// - SYSCALL
		// - CWD
		// - PATH 0 is path of <src> relative to <cwd>
		// - PATH 1 is directory of <dst>
		// - PATH 2 is path of <dst> relative to <cwd>
		// - EOE

		String time = eventData.get(AuditEventReader.TIME);
		String eventId = eventData.get(AuditEventReader.EVENT_ID);
		
		PathRecord srcPathRecord = PathRecord.getPathWithItemNumber(eventData, 0);
		PathRecord dstPathRecord = PathRecord.getPathWithItemNumber(eventData, 2);
		
		if(srcPathRecord == null){
			log(Level.WARNING, "Missing source PATH record", null, time, eventId, syscall);
			return;
		}
		
		if(dstPathRecord == null){
			log(Level.WARNING, "Missing destination PATH record", null, time, eventId, syscall);
			return;
		}
		
		ArtifactIdentifier srcArtifactIdentifier = null;
		ArtifactIdentifier dstArtifactIdentifier = null;
		
		if(syscall == SYSCALL.LINK || syscall == SYSCALL.SYMLINK){
			srcArtifactIdentifier = resolvePath(srcPathRecord, eventData, syscall);
			dstArtifactIdentifier = resolvePath(dstPathRecord, eventData, syscall);
		}else if(syscall == SYSCALL.LINKAT){
			srcArtifactIdentifier = resolvePath_At(srcPathRecord, AuditEventReader.ARG0, eventData, syscall);
			dstArtifactIdentifier = resolvePath_At(dstPathRecord, AuditEventReader.ARG2, eventData, syscall);
		}else if(syscall == SYSCALL.SYMLINKAT){
			srcArtifactIdentifier = resolvePath(srcPathRecord, eventData, syscall);
			dstArtifactIdentifier = resolvePath_At(dstPathRecord, AuditEventReader.ARG1, eventData, syscall);
		}else{
			log(Level.WARNING, "Unexpected syscall '"+syscall+"' in LINK/SYMLINK handler", null, time, eventId, syscall);
			return;
		}

		if(srcArtifactIdentifier == null || dstArtifactIdentifier == null){
			return;
		}

		handleSpecialSyscalls(eventData, syscall, 
				srcPathRecord, srcArtifactIdentifier, 
				dstPathRecord, dstArtifactIdentifier);
	}

	/**
	 * Creates OPM vertices and edges for link, symlink, linkat, symlinkat, rename, renameat syscalls.
	 * 
	 * Steps:
	 * 1) Gets the valid source artifact type (can be either file, named pipe, unix socket)
	 * 2) Creates a valid destination artifact type with the same type as the source artifact type
	 * 2.a) Marks new epoch for the destination file
	 * 3) Adds the process vertex, artifact vertices, and edges (Process to srcArtifact [Used], Process to dstArtifact [WasGeneratedBy], dstArtifact to oldArtifact [WasDerivedFrom])
	 * to reporter's internal buffer.
	 * 
	 * @param eventData event data as gotten from the audit log
	 * @param syscall syscall being handled
	 * @param srcPath path of the file being linked
	 * @param dstPath path of the link
	 */
	private void handleSpecialSyscalls(Map<String, String> eventData, SYSCALL syscall, 
			PathRecord srcPathRecord, ArtifactIdentifier srcArtifactIdentifier, 
			PathRecord dstPathRecord, ArtifactIdentifier dstArtifactIdentifier){

		if(eventData == null || syscall == null || srcArtifactIdentifier == null || dstArtifactIdentifier == null){
			logger.log(Level.INFO, "Missing arguments. srcPath:{0}, dstPath:{1}, syscall:{2}, eventData:{3}", 
					new Object[]{srcArtifactIdentifier, dstArtifactIdentifier, syscall, eventData});
			return;
		}

		String eventId = eventData.get(AuditEventReader.EVENT_ID);
		String time = eventData.get(AuditEventReader.TIME);
		String pid = eventData.get(AuditEventReader.PID);

		if(eventId == null || time == null || pid == null){
			log(Level.INFO, "Missing keys in event data. pid:"+pid, null, time, eventId, syscall);
			return;
		}

		Process process = processManager.handleProcessFromSyscall(eventData);

		//destination is new so mark epoch
		artifactManager.artifactCreated(dstArtifactIdentifier);

		artifactManager.artifactPermissioned(srcArtifactIdentifier, srcPathRecord.getPermissions());
		Artifact srcVertex = putArtifactFromSyscall(eventData, srcArtifactIdentifier);
		Used used = new Used(process, srcVertex);
		putEdge(used, getOperation(syscall, SYSCALL.READ), time, eventId, AUDIT_SYSCALL_SOURCE);

		artifactManager.artifactPermissioned(dstArtifactIdentifier, dstPathRecord.getPermissions());
		Artifact dstVertex = putArtifactFromSyscall(eventData, dstArtifactIdentifier);
		WasGeneratedBy wgb = new WasGeneratedBy(dstVertex, process);
		putEdge(wgb, getOperation(syscall, SYSCALL.WRITE), time, eventId, AUDIT_SYSCALL_SOURCE);

		WasDerivedFrom wdf = new WasDerivedFrom(dstVertex, srcVertex);
		wdf.addAnnotation(OPMConstants.EDGE_PID, pid);
		putEdge(wdf, getOperation(syscall), time, eventId, AUDIT_SYSCALL_SOURCE);
	}

	private void handleChmod(Map<String, String> eventData, SYSCALL syscall) {
		// chmod(), fchmod(), and fchmodat() receive the following message(s):
		// - SYSCALL
		// - CWD
		// - PATH
		// - EOE
		String eventId = eventData.get(AuditEventReader.EVENT_ID);
		String time = eventData.get(AuditEventReader.TIME);
		String pid = eventData.get(AuditEventReader.PID);
		String modeArgument = null;
		
		PathRecord pathRecord = PathRecord.getFirstPathWithNametype(eventData, AuditEventReader.NAMETYPE_NORMAL);
		
		// if syscall is chmod, then path is <path0> relative to <cwd>
		// if syscall is fchmod, look up file descriptor which is <a0>
		// if syscall is fchmodat, loop up the directory fd and build a path using the path in the audit log
		ArtifactIdentifier artifactIdentifier = null;
		if(syscall == SYSCALL.CHMOD){
			artifactIdentifier = resolvePath(pathRecord, eventData, syscall);
			modeArgument = eventData.get(AuditEventReader.ARG1);
		}else if(syscall == SYSCALL.FCHMOD){
			String fd = eventData.get(AuditEventReader.ARG0);
			FileDescriptor fileDescriptor = processManager.getFd(pid, fd);
			fileDescriptor = fileDescriptor == null ? addUnknownFd(pid, fd) : fileDescriptor;
			artifactIdentifier = fileDescriptor.identifier;
			modeArgument = eventData.get(AuditEventReader.ARG1);
		}else if(syscall == SYSCALL.FCHMODAT){
			artifactIdentifier = resolvePath_At(pathRecord, AuditEventReader.ARG0, eventData, syscall);
			modeArgument = eventData.get(AuditEventReader.ARG2);
		}else{
			log(Level.INFO, "Unexpected syscall '"+syscall+"' in CHMOD handler", null, time, eventId, syscall);
			return;
		}

		if(artifactIdentifier == null){
			logger.log(Level.WARNING, "Failed to process syscall="+syscall +" because of missing artifact identifier");
			return;
		}
		
		String mode = new BigInteger(modeArgument).toString(8);
		String permissions = PathRecord.parsePermissions(mode);
		
		artifactManager.artifactVersioned(artifactIdentifier);
		artifactManager.artifactPermissioned(artifactIdentifier, permissions);
		
		Process process = processManager.handleProcessFromSyscall(eventData);
		Artifact vertex = putArtifactFromSyscall(eventData, artifactIdentifier);
		WasGeneratedBy wgb = new WasGeneratedBy(vertex, process);
		wgb.addAnnotation(OPMConstants.EDGE_MODE, mode);
		putEdge(wgb, getOperation(syscall), time, eventId, AUDIT_SYSCALL_SOURCE);
	}
	
	private void handleKill(Map<String, String> eventData, SYSCALL syscall){
		// kill() receives the following message(s):
		// - SYSCALL
		// - EOE

		/*
		 * a0   = pid 
		 * 0    - targetPid = caller pid
		 * -1   - to all processes to which the called pid can send the signal to
		 * -pid - send to pid and the group of the pid
		 */
		
		// Only handling successful ones
		if("0".equals(eventData.get(AuditEventReader.EXIT))){
			String time = eventData.get(AuditEventReader.TIME);
			String eventId = eventData.get(AuditEventReader.EVENT_ID);

			String targetPidStr = eventData.get(AuditEventReader.ARG0);
			
			Process targetProcess = null;
			try{
				int targetPidInt = Integer.parseInt(targetPidStr);
				if(targetPidInt < -1){
					targetPidInt = targetPidInt * -1;
					targetPidStr = String.valueOf(targetPidInt);
					targetProcess = processManager.getVertex(targetPidStr);
				}else if(targetPidInt == 0){
					String pid = eventData.get(AuditEventReader.PID);
					targetPidStr = pid;
					targetProcess = processManager.getVertex(targetPidStr);
				}else if(targetPidInt == -1){
					// Unhandled TODO
					// Let targetProcess stay null and it won't be sent to OPM
					logger.log(Level.INFO, "'-1' target pid");
				}else{
					// Regular non-negative and non-zero pid
					targetProcess = processManager.getVertex(targetPidStr);
				}
			}catch(Exception e){
				log(Level.WARNING, "Invalid target pid(a0): " + targetPidStr, null, time, eventId, syscall);
			}

			// If the target process hasn't been seen yet then can't draw an edge because don't 
			// have enough annotations for the target process.
			if(targetProcess != null){
				String signalString = eventData.get(AuditEventReader.ARG1);
				Integer signal = HelperFunctions.parseInt(signalString, null);
				
				if(signal != null){
					String signalAnnotation = String.valueOf(signal);
					if(signalAnnotation != null){
						Process actingProcess = processManager.handleProcessFromSyscall(eventData);
						
						String operation = getOperation(syscall);
						
						WasTriggeredBy edge = new WasTriggeredBy(targetProcess, actingProcess);
						edge.addAnnotation(OPMConstants.EDGE_SIGNAL, signalAnnotation);
						
						putEdge(edge, operation, time, eventId, AUDIT_SYSCALL_SOURCE);
					}
				}
			}
		}
	}
	
	private void handlePtrace(Map<String, String> eventData, SYSCALL syscall){
		// ptrace() receives the following message(s):
		// - SYSCALL
		// - EOE
		String targetPid = eventData.get(AuditEventReader.ARG1);
		Process targetProcess = processManager.getVertex(targetPid);
		
		// If the target process hasn't been seen yet then can't draw an edge because don't 
		// have enough annotations for the target process.
		if(targetProcess != null){
			String actionString = eventData.get(AuditEventReader.ARG0);
			Integer action = HelperFunctions.parseInt(actionString, null);
			
			// If the action argument is valid only then can continue because only handling some
			if(action != null){
				String actionAnnotation = ptraceActions.get(action);
				// If this is one of the actions that needs to be handled then it won't be null
				if(actionAnnotation != null){
					Process actingProcess = processManager.handleProcessFromSyscall(eventData);
					
					String time = eventData.get(AuditEventReader.TIME);
					String eventId = eventData.get(AuditEventReader.EVENT_ID);
					String operation = getOperation(syscall);
					
					WasTriggeredBy edge = new WasTriggeredBy(targetProcess, actingProcess);
					edge.addAnnotation(OPMConstants.EDGE_REQUEST, actionAnnotation);
					
					putEdge(edge, operation, time, eventId, AUDIT_SYSCALL_SOURCE);
				}
			}
		}
	}
	
	private void handleSocketPair(Map<String, String> eventData, SYSCALL syscall){
		// socketpair() receives the following message(s):
		// - SYSCALL
		// - FD_PAIR
		// - EOE
		String pid = eventData.get(AuditEventReader.PID);
		String fd0 = eventData.get(AuditEventReader.FD0);
		String fd1 = eventData.get(AuditEventReader.FD1);
		String domainString = eventData.get(AuditEventReader.ARG0);
		String sockTypeString = eventData.get(AuditEventReader.ARG1);
		String fdTgid = processManager.getFdTgid(pid);
		
		int domain = HelperFunctions.parseInt(domainString, null); // Let exception be thrown
		int sockType = HelperFunctions.parseInt(sockTypeString, null);
		
		String protocol = getProtocolNameBySockType(sockType);
		
		ArtifactIdentifier fdIdentifier = null;
		
		if(domain == AF_INET || domain == AF_INET6 || domain == PF_INET || domain == PF_INET6){
			fdIdentifier = new UnnamedNetworkSocketPairIdentifier(fdTgid, fd0, fd1, protocol);
		}else if(domain == AF_LOCAL || domain == AF_UNIX || domain == PF_LOCAL || domain == PF_UNIX){
			fdIdentifier = new UnnamedUnixSocketPairIdentifier(fdTgid, fd0, fd1);
		}else{
			// Unsupported domain
		}
		
		if(fdIdentifier != null){
			processManager.setFd(pid, fd0, new FileDescriptor(fdIdentifier, false));
			processManager.setFd(pid, fd1, new FileDescriptor(fdIdentifier, false));
			
			artifactManager.artifactCreated(fdIdentifier);
		}
	}

	private void handlePipe(Map<String, String> eventData, SYSCALL syscall) {
		// pipe() receives the following message(s):
		// - SYSCALL
		// - FD_PAIR
		// - EOE
		String pid = eventData.get(AuditEventReader.PID);
		String fdTgid = processManager.getFdTgid(pid);
		String fd0 = eventData.get(AuditEventReader.FD0);
		String fd1 = eventData.get(AuditEventReader.FD1);
		ArtifactIdentifier readPipeIdentifier = new UnnamedPipeIdentifier(fdTgid, fd0, fd1);
		ArtifactIdentifier writePipeIdentifier = new UnnamedPipeIdentifier(fdTgid, fd0, fd1);
		processManager.setFd(pid, fd0, new FileDescriptor(readPipeIdentifier, true));
		processManager.setFd(pid, fd1, new FileDescriptor(writePipeIdentifier, false));

		// Since both (read, and write) pipe identifiers are the same, only need to mark epoch on one.
		artifactManager.artifactCreated(readPipeIdentifier);
	}
	
	private NetworkSocketIdentifier getNetworkIdentifier(SYSCALL syscall, String time, String eventId, 
			String pid, String fd){
		FileDescriptor fileDescriptor = processManager.getFd(pid, fd);
		if(fileDescriptor != null){
			if(fileDescriptor.identifier.getClass().equals(NetworkSocketIdentifier.class)){
				return ((NetworkSocketIdentifier)fileDescriptor.identifier);
			}else{
				log(Level.INFO, "Expected network identifier but found: " + 
						fileDescriptor.identifier.getClass(), null, time, eventId, syscall);
				return null;
			}
		}else{
			return null;
		}
	}
	
	private String getProtocol(SYSCALL syscall, String time, String eventId, String pid, String fd){
		NetworkSocketIdentifier identifier = getNetworkIdentifier(syscall, time, eventId, pid, fd);
		if(identifier != null){
			return identifier.getProtocol();
		}else{
			return null;
		}
	}
	
	private AddressPort getLocalAddressPort(SYSCALL syscall, String time, String eventId, String pid, String fd){
		NetworkSocketIdentifier identifier = getNetworkIdentifier(syscall, time, eventId, pid, fd);
		if(identifier != null){
			NetworkSocketIdentifier networkIdentifier = ((NetworkSocketIdentifier)identifier);
			return new AddressPort(networkIdentifier.getLocalHost(), networkIdentifier.getLocalPort());
		}else{
			return null;
		}
	}
	
	private String getNetworkNamespaceForPid(String pid){
		if(HANDLE_NAMESPACES){
			return processManager.getNetNamespace(pid);
		}else{
			return null;
		}
	}
	
	private void handleSocket(Map<String, String> eventData, SYSCALL syscall){
		// socket() receives the following message(s):
		// - SYSCALL
		// - EOE
		String sockFd = eventData.get(AuditEventReader.EXIT);
		Integer socketType = HelperFunctions.parseInt(eventData.get(AuditEventReader.ARG1), null);
		String protocolName = getProtocolNameBySockType(socketType);
		
		if(protocolName != null){
			String pid = eventData.get(AuditEventReader.PID);

			NetworkSocketIdentifier identifierForProtocol = new NetworkSocketIdentifier(
					null, null, null, null, protocolName, getNetworkNamespaceForPid(eventData.get(AuditEventReader.PID)));
			processManager.setFd(pid, sockFd, new FileDescriptor(identifierForProtocol, null)); // no close edge
		}
	}
	
	private void putBind(String pid, String fd, ArtifactIdentifier identifier){
		if(identifier != null){
			// no need to add to descriptors because we will have the address from other syscalls? TODO
			processManager.setFd(pid, fd, new FileDescriptor(identifier, null));
			if(identifier instanceof UnixSocketIdentifier){
				artifactManager.artifactCreated(identifier);
			}
		}
	}

	// needed for marking epoch for unix socket
	private void handleBindKernelModule(Map<String, String> eventData, String time, String eventId, SYSCALL syscall,
			String pid, String exit, String sockFd, int sockType, String localSaddr, String remoteSaddr){
		boolean isNetwork = isNetworkSaddr(localSaddr) || isNetworkSaddr(remoteSaddr);
		if(!isNetwork){
			// is unix
			ArtifactIdentifier identifier = parseUnixSaddr(pid, localSaddr); // local or remote. any is fine.
			if(identifier != null){
				putBind(pid, sockFd, identifier);
			}else{
				logInvalidSaddr(remoteSaddr, time, eventId, syscall);
			}
		}else{
			// nothing needed in case of network because we get all info from other syscalls if kernel module
		}
	}
	
	private void handleBind(Map<String, String> eventData, SYSCALL syscall) {
		// bind() receives the following message(s):
		// - SYSCALL
		// - SADDR
		// - EOE
		String time = eventData.get(AuditEventReader.TIME);
		String eventId = eventData.get(AuditEventReader.EVENT_ID);
		String saddr = eventData.get(AuditEventReader.SADDR);
		String sockFd = eventData.get(AuditEventReader.ARG0);
		String pid = eventData.get(AuditEventReader.PID);

		if(!isNetlinkSaddr(saddr)){ // not handling netlink
			ArtifactIdentifier identifier = null;
			if(isNetworkSaddr(saddr)){
				AddressPort addressPort = parseNetworkSaddr(saddr);
				if(addressPort != null){
					String protocolName = getProtocol(syscall, time, eventId, pid, sockFd);
					identifier = new NetworkSocketIdentifier(
							addressPort.address, addressPort.port, null, null, protocolName,
							getNetworkNamespaceForPid(pid));
				}
			}else if(isUnixSaddr(saddr)){
				identifier = parseUnixSaddr(pid, saddr);
			}
			
			if(identifier == null){
				logInvalidSaddr(saddr, time, eventId, syscall);
			}else{
				putBind(pid, sockFd, identifier);
			}
		}
	}
	
	private NetworkSocketIdentifier constructNetworkIdentifier(SYSCALL syscall, String time, String eventId, 
			String localSaddr, String remoteSaddr, Integer sockType, String pid){
		String protocolName = getProtocolNameBySockType(sockType);
		AddressPort local = parseNetworkSaddr(localSaddr);
		AddressPort remote = parseNetworkSaddr(remoteSaddr);
		if(local == null && remote == null){
			log(Level.INFO, "Local and remote saddr both null", null, time, eventId, syscall);
		}else{
			String localAddress = null, localPort = null, remoteAddress = null, remotePort = null;
			if(local != null){
				localAddress = local.address;
				localPort = local.port;
			}
			if(remote != null){
				remoteAddress = remote.address;
				remotePort = remote.port;
			}
			return new NetworkSocketIdentifier(localAddress, localPort, 
					remoteAddress, remotePort, protocolName, getNetworkNamespaceForPid(pid));
		}
		return null;
	}
		
	private void putConnect(SYSCALL syscall, String time, String eventId, String pid, String fd, 
			ArtifactIdentifier fdIdentifier, Map<String, String> eventData){
		if(fdIdentifier != null){
			if(fdIdentifier instanceof NetworkSocketIdentifier){
				artifactManager.artifactCreated(fdIdentifier);
			}
			processManager.setFd(pid, fd, new FileDescriptor(fdIdentifier, false));
			
			Process process = processManager.handleProcessFromSyscall(eventData);
			artifactManager.artifactVersioned(fdIdentifier);
			Artifact artifact = putArtifactFromSyscall(eventData, fdIdentifier);
			WasGeneratedBy wgb = new WasGeneratedBy(artifact, process);
			putEdge(wgb, getOperation(syscall), time, eventId, AUDIT_SYSCALL_SOURCE);
			
			if(HANDLE_NETFILTER_HOOKS){
				try{
					netfilterHooksManager.handleNetworkSyscallEvent(time, eventId, false, artifact);
				}catch(Exception e){
					log(Level.SEVERE, "Unexpected error", e, time, eventId, syscall);
				}
			}
		}
	}
	
	private void handleConnectKernelModule(Map<String, String> eventData, String time, String eventId, SYSCALL syscall,
			String pid, String exit, String sockFd, int sockType, String localSaddr, String remoteSaddr){
		// if not network then unix. Only that being handled in kernel module
		boolean isNetwork = isNetworkSaddr(localSaddr) || isNetworkSaddr(remoteSaddr);
		
		ArtifactIdentifier identifier = null;
		
		if(isNetwork){
			identifier = constructNetworkIdentifier(syscall, time, eventId, localSaddr, remoteSaddr, sockType, pid);
		}else{ // is unix socket
			identifier = parseUnixSaddr(pid, remoteSaddr); // address in remote unlike accept
			if(identifier == null){
				logInvalidSaddr(localSaddr, time, eventId, syscall);
			}
		}
		if(identifier != null){
			putConnect(syscall, time, eventId, pid, sockFd, identifier, eventData);
		}
	}

	private void handleConnect(Map<String, String> eventData, SYSCALL syscall){
		//connect() receives the following message(s):
		// - SYSCALL
		// - SADDR
		// - EOE	
		String eventId = eventData.get(AuditEventReader.EVENT_ID);
		String time = eventData.get(AuditEventReader.TIME);
		String pid = eventData.get(AuditEventReader.PID);
		String saddr = eventData.get(AuditEventReader.SADDR);
		String sockFd = eventData.get(AuditEventReader.ARG0);
		
		Integer exit = HelperFunctions.parseInt(eventData.get(AuditEventReader.EXIT), null);
		if(exit == null){
			log(Level.WARNING, "Failed to parse exit value: " + eventData.get(AuditEventReader.EXIT), 
					null, time, eventId, syscall);
			return;
		}else{ // not null
			// only handling if success is 0 or success is EINPROGRESS
			if(exit != 0 // no success
					&& exit != EINPROGRESS){ //in progress with possible failure in the future. see manpage.
				return;
			}
		}

		if(!isNetlinkSaddr(saddr)){ // not handling netlink saddr
			ArtifactIdentifier identifier = null;
			if(isNetworkSaddr(saddr)){
				AddressPort addressPort = parseNetworkSaddr(saddr);
				if(addressPort != null){
					String protocolName = getProtocol(syscall, time, eventId, pid, sockFd);
					identifier = new NetworkSocketIdentifier(
							null, null, addressPort.address, addressPort.port, protocolName, getNetworkNamespaceForPid(pid));
					
				}
			}else if(isUnixSaddr(saddr)){
				identifier = parseUnixSaddr(pid, saddr);
			}
			
			if(identifier == null){
				logInvalidSaddr(saddr, time, eventId, syscall);
			}else{
				putConnect(syscall, time, eventId, pid, sockFd, identifier, eventData);
			}
		}
	}

	private void putAccept(SYSCALL syscall, String time, String eventId, String pid, String fd, 
			ArtifactIdentifier fdIdentifier, Map<String, String> eventData){
		// eventData must contain all information need to create a process vertex
		if(fdIdentifier != null){
			if(fdIdentifier instanceof NetworkSocketIdentifier){
				artifactManager.artifactCreated(fdIdentifier);
			}
			
			processManager.setFd(pid, fd, new FileDescriptor(fdIdentifier, false));
			
			Process process = processManager.handleProcessFromSyscall(eventData);
			Artifact socket = putArtifactFromSyscall(eventData, fdIdentifier);
			Used used = new Used(process, socket);
			putEdge(used, getOperation(syscall), time, eventId, AUDIT_SYSCALL_SOURCE);
			
			if(HANDLE_NETFILTER_HOOKS){
				try{
					netfilterHooksManager.handleNetworkSyscallEvent(time, eventId, true, socket);
				}catch(Exception e){
					log(Level.SEVERE, "Unexpected error", e, time, eventId, syscall);
				}
			}
		}
	}
	
	private void handleAcceptKernelModule(Map<String, String> eventData, String time, String eventId, SYSCALL syscall,
			String pid, String fd, String sockFd, int sockType, String localSaddr, String remoteSaddr){
		// if not network then unix. Only that being handled in kernel module
		boolean isNetwork = isNetworkSaddr(localSaddr) || isNetworkSaddr(remoteSaddr);
		
		ArtifactIdentifier identifier = null;
		if(isNetwork){
			identifier = constructNetworkIdentifier(syscall, time, eventId, localSaddr, remoteSaddr, sockType, pid);
		}else{ // is unix socket
			identifier = parseUnixSaddr(pid, localSaddr);
			if(identifier == null){
				logInvalidSaddr(localSaddr, time, eventId, syscall);
			}
		}
		if(identifier != null){
			putAccept(syscall, time, eventId, pid, fd, identifier, eventData);
		}
	}
	
	private void handleAccept(Map<String, String> eventData, SYSCALL syscall) {
		//accept() & accept4() receive the following message(s):
		// - SYSCALL
		// - SADDR
		// - EOE
		String eventId = eventData.get(AuditEventReader.EVENT_ID);
		String time = eventData.get(AuditEventReader.TIME);
		String pid = eventData.get(AuditEventReader.PID);
		String sockFd = eventData.get(AuditEventReader.ARG0); //the fd on which the connection was accepted, not the fd of the connection
		String fd = eventData.get(AuditEventReader.EXIT); //fd of the connection
		String saddr = eventData.get(AuditEventReader.SADDR);

		if(!isNetlinkSaddr(saddr)){ // not handling netlink saddr
			ArtifactIdentifier identifier = null;
			FileDescriptor boundFileDescriptor = processManager.getFd(pid, sockFd);
			if(isNetworkSaddr(saddr)){
				AddressPort addressPort = parseNetworkSaddr(saddr);
				if(addressPort != null){
					String localAddress = null, localPort = null, 
							protocol = getProtocol(syscall, time, eventId, pid, sockFd);
					AddressPort localAddressPort = getLocalAddressPort(syscall, time, eventId, pid, sockFd);
					if(localAddressPort != null){
						localAddress = localAddressPort.address;
						localPort = localAddressPort.port;
					}
					identifier = new NetworkSocketIdentifier(localAddress, localPort, 
							addressPort.address, addressPort.port, protocol, getNetworkNamespaceForPid(pid));
				}
			}else if(isUnixSaddr(saddr)){
				// The unix saddr in accept is empty. So, use the bound one.
				if(boundFileDescriptor != null){
					if(boundFileDescriptor.identifier.getClass().equals(UnixSocketIdentifier.class)){
						// Use a new one because the wasOpenedForRead is updated otherwise it would
						// be updated in the bound identifier too.
						UnixSocketIdentifier boundUnixIdentifier = (UnixSocketIdentifier)(boundFileDescriptor.identifier);
						identifier = new UnixSocketIdentifier(boundUnixIdentifier.path, boundUnixIdentifier.rootFSPath);
					}else{
						log(Level.INFO, "Expected unix identifier but found: " + 
								boundFileDescriptor.identifier.getClass(), null, time, eventId, syscall);
					}
				}
			}
			
			if(identifier == null){
				logInvalidSaddr(saddr, time, eventId, syscall);
			}else{
				putAccept(syscall, time, eventId, pid, fd, identifier, eventData);
			}
		}
	}
	
	private ArtifactIdentifier getNetworkIdentifierFromFdAndOrSaddr(SYSCALL syscall, String time, String eventId,
			String pid, String fd, String saddr){
		ArtifactIdentifier identifier = null;
		if(saddr != null){
			if(!isNetlinkSaddr(saddr)){ // not handling netlink saddr
				if(isNetworkSaddr(saddr)){
					AddressPort addressPort = parseNetworkSaddr(saddr);
					if(addressPort != null){
						String localAddress = null, localPort = null;
						//	Protocol has to be UDP since SOCK_DGRAM and (AF_INET or AF_INET6) 
						//	protocol = getProtocol(syscall, time, eventId, pid, fd);
						AddressPort localAddressPort = getLocalAddressPort(syscall, time, eventId, pid, fd);
						if(localAddressPort != null){
							localAddress = localAddressPort.address;
							localPort = localAddressPort.port;
						}
						// Protocol can only be UDP because saddr only present when SOCK_DGRAM
						// and family is AF_INET or AF_INET6.
						identifier = new NetworkSocketIdentifier(localAddress, localPort, 
								addressPort.address, addressPort.port, PROTOCOL_NAME_UDP, getNetworkNamespaceForPid(pid));
					}
				}else if(isUnixSaddr(saddr)){
					identifier = parseUnixSaddr(pid, saddr);
					// just use this
				}
			}
		}else{
			// use fd
			FileDescriptor fileDescriptor = processManager.getFd(pid, fd);
			if(fileDescriptor != null){
				identifier = fileDescriptor.identifier;
			}
		}
	
		// Don't update fd in descriptors even if updated identifier
		// Not updating because if fd used again then saddr must be present
		return identifier;
	}
	
	private void handleNetworkIOKernelModule(Map<String, String> eventData, String time, String eventId, SYSCALL syscall,
			String pid, String bytes, String sockFd, int sockType, String localSaddr, String remoteSaddr,
			boolean isRecv){
		boolean isNetwork = isNetworkSaddr(localSaddr) || isNetworkSaddr(remoteSaddr);
		ArtifactIdentifier identifier = null;
		if(isNetwork){
			NetworkSocketIdentifier recordIdentifier =
					constructNetworkIdentifier(syscall, time, eventId, localSaddr, remoteSaddr, sockType, pid);
			FileDescriptor fileDescriptor = processManager.getFd(pid, sockFd);
			if(fileDescriptor != null && fileDescriptor.identifier instanceof NetworkSocketIdentifier){
				NetworkSocketIdentifier fdNetworkIdentifier = (NetworkSocketIdentifier)fileDescriptor.identifier;
				if(HelperFunctions.isNullOrEmpty(fdNetworkIdentifier.getRemoteHost())){
					// Connection based IO
					identifier = recordIdentifier;
				}else{
					// Non-connection based IO
					identifier = fdNetworkIdentifier;
				}
			}else{
				identifier = recordIdentifier;
			}
		}else{ // is unix socket
			identifier = parseUnixSaddr(pid, localSaddr);
			if(identifier == null){
				logInvalidSaddr(localSaddr, time, eventId, syscall);
			}
		}
		putIO(eventData, time, eventId, syscall, pid, sockFd, identifier, bytes, null, isRecv);
	}
	
	private void putIO(Map<String, String> eventData, String time, String eventId, SYSCALL syscall,
			String pid, String fd, ArtifactIdentifier identifier, 
			String bytesTransferred, String offset, boolean incoming){

		boolean isNetworkUdp = false;
		if(identifier instanceof NetworkSocketIdentifier){
			if(!USE_SOCK_SEND_RCV){
				return;
			}
			isNetworkUdp = isUdp(identifier);
		}else{ // all else are local i.e. file io include unix
			if(!USE_READ_WRITE){
				return;
			}
			if(identifier instanceof UnixSocketIdentifier || identifier instanceof UnnamedUnixSocketPairIdentifier){
				if(!globals.unixSockets){
					return;
				}
			}
		}
		
		if(identifier == null){
			identifier = addUnknownFd(pid, fd).identifier;
		}
		
		if(isNetworkUdp){
			// Since saddr present that means that it is SOCK_DGRAM.
			// Epoch for all SOCK_DGRAM
			artifactManager.artifactCreated(identifier);
		}

		Process process = processManager.handleProcessFromSyscall(eventData);
		Artifact artifact = null;
		AbstractEdge edge = null;
		if(incoming){
			artifact = putArtifactFromSyscall(eventData, identifier);
			edge = new Used(process, artifact);
		}else{
			artifactManager.artifactVersioned(identifier);
			artifact = putArtifactFromSyscall(eventData, identifier);
			edge = new WasGeneratedBy(artifact, process);
		}
		edge.addAnnotation(OPMConstants.EDGE_SIZE, bytesTransferred);
		if(offset != null){
			edge.addAnnotation(OPMConstants.EDGE_OFFSET, offset);	
		}
		putEdge(edge, getOperation(syscall), time, eventId, AUDIT_SYSCALL_SOURCE);
		
		// UDP
		if(isNetworkUdp && HANDLE_NETFILTER_HOOKS){
			try{
				netfilterHooksManager.handleNetworkSyscallEvent(time, eventId, incoming, artifact);
			}catch(Exception e){
				log(Level.SEVERE, "Unexpected error", e, time, eventId, syscall);
			}
		}
	}

	/**
	 * Outputs formatted messages in the format-> [Event ID:###, SYSCALL:... MSG Exception]
	 *  
	 * @param level Level of the log message
	 * @param msg Message to print
	 * @param exception Exception (if any)
	 * @param time time of the audit event
	 * @param eventId id of the audit event
	 * @param syscall system call of the audit event
	 */
	public void log(Level level, String msg, Exception exception, String time, String eventId, SYSCALL syscall){
		String msgPrefix = "";
		if(eventId != null && syscall != null){
			msgPrefix = "[Time:EventID="+time+":"+eventId+", SYSCALL="+syscall+"] ";
		}else if(eventId != null && syscall == null){
			msgPrefix = "[Time:EventID="+time+":"+eventId+"] ";
		}else if(eventId == null && syscall != null){
			msgPrefix = "[SYSCALL="+syscall+"] ";
		}
		if(exception == null){
			logger.log(level, msgPrefix + msg);
		}else{
			logger.log(level, msgPrefix + msg, exception);
		}
	}
	
	public final Artifact putArtifactFromSyscall(Map<String, String> eventData, ArtifactIdentifier identifier){
		String time = eventData.get(AuditEventReader.TIME);
		String eventId = eventData.get(AuditEventReader.EVENT_ID);
		String pid = eventData.get(AuditEventReader.PID);
		String source = AUDIT_SYSCALL_SOURCE;
		String operation = getOperation(SYSCALL.UPDATE);
		return artifactManager.putArtifact(time, eventId, operation, pid, source, identifier);
	}
	
	/**
	 * Standardized method to be called for putting an edge into the reporter's buffer.
	 * Adds the arguments to the edge with proper annotations. If any argument null then 
	 * that annotation isn't put to the edge.
	 * 
	 * @param edge edge to add annotations to and to put to reporter's buffer
	 * @param operation operation as gotten from {@link #getOperation(SYSCALL) getOperation}
	 * @param time time of the audit log which generated this edge
	 * @param eventId event id in the audit log which generated this edge
	 * @param source source of the edge
	 */
	public void putEdge(AbstractEdge edge, String operation, String time, String eventId, String source){
		if(edge != null && edge.getChildVertex() != null && edge.getParentVertex() != null){
			if(!globals.unixSockets && 
					(isUnixSocketArtifact(edge.getChildVertex()) ||
							isUnixSocketArtifact(edge.getParentVertex()))){
				return;
			}
			if(time != null){
				edge.addAnnotation(OPMConstants.EDGE_TIME, time);
			}
			if(eventId != null){
				edge.addAnnotation(OPMConstants.EDGE_EVENT_ID, eventId);
			}
			if(source != null){
				edge.addAnnotation(OPMConstants.SOURCE, source);
			}
			if(operation != null){
				edge.addAnnotation(OPMConstants.EDGE_OPERATION, operation);
			}
			putEdge(edge);
		}else{
			log(Level.WARNING, "Failed to put edge. edge = "+edge+", sourceVertex = "+(edge != null ? edge.getChildVertex() : null)+", "
					+ "destination vertex = "+(edge != null ? edge.getParentVertex() : null)+", operation = "+operation+", "
					+ "time = "+time+", eventId = "+eventId+", source = " + source, null, time, eventId, SYSCALL.valueOf(operation.toUpperCase()));
		}
	}
	
	private boolean isUnixSocketArtifact(AbstractVertex vertex){
		return vertex != null && 
				(OPMConstants.SUBTYPE_UNIX_SOCKET.equals(vertex.getAnnotation(OPMConstants.ARTIFACT_SUBTYPE))
						|| OPMConstants.SUBTYPE_UNNAMED_UNIX_SOCKET_PAIR.equals(vertex.getAnnotation(OPMConstants.ARTIFACT_SUBTYPE)));
	}
	
	private AddressPort parseNetworkSaddr(String saddr){
		// TODO the address = 0.0.0.0 and 127.0.0.1 issue! Rename or not?
		try{
			String address = null, port = null;
			if (isIPv4Saddr(saddr) && saddr.length() >= 17){
				port = Integer.toString(Integer.parseInt(saddr.substring(4, 8), 16));
				int oct1 = Integer.parseInt(saddr.substring(8, 10), 16);
				int oct2 = Integer.parseInt(saddr.substring(10, 12), 16);
				int oct3 = Integer.parseInt(saddr.substring(12, 14), 16);
				int oct4 = Integer.parseInt(saddr.substring(14, 16), 16);
				address = String.format("%d.%d.%d.%d", oct1, oct2, oct3, oct4);
			}else if(isIPv6Saddr(saddr) && saddr.length() >= 49){
				port = Integer.toString(Integer.parseInt(saddr.substring(4, 8), 16));
				String hextet1 = saddr.substring(16, 20);
				String hextet2 = saddr.substring(20, 24);
				String hextet3 = saddr.substring(24, 28);
				String hextet4 = saddr.substring(28, 32);
				String hextet5 = saddr.substring(32, 36);
				String hextet6 = saddr.substring(36, 40);
				String hextet7 = saddr.substring(40, 44);
				String hextet8 = saddr.substring(44, 48);
				address = String.format("%s:%s:%s:%s:%s:%s:%s:%s", hextet1, hextet2, hextet3, hextet4,
						hextet5, hextet6, hextet7, hextet8);
			}
			if(address != null && port != null){
				return new AddressPort(address, port);
			}
		}catch(Exception e){
			// Logged by the caller
		}
		return null;
	}
	
	private UnixSocketIdentifier parseUnixSaddr(String pid, String saddr){
		String path = "";
		int start = -1;
		//starting from 2 since first two characters are 01
		for(int a = 2;a <= saddr.length()-2; a+=2){
			if(saddr.substring(a,a+2).equals("00")){ //null char
				//continue until non-null found
				continue;
			}else{
				//first non-null char i.e. we are going to start from here
				start = a;
				break;
			}
		}
		
		if(start != -1){ //found
			try{
				for(; start <= saddr.length() - 2; start+=2){
					char c = (char)(Integer.parseInt(saddr.substring(start, start+2), 16));
					if(c == 0){ //null char
						break;
					}
					path += c;
				}
			}catch(Exception e){
				return null;
			}
		}

		// TODO handle unnamed unix socket. Need a new identifier to contain the tgid like for memory identifier.
		// Unnamed unix socket created through socketpair.
		// TODO need to handle socketpair syscall for that too.
		if(path != null && !path.isEmpty()){
			String rootFSPath = processManager.getRoot(pid);
			return new UnixSocketIdentifier(path, rootFSPath);
		}else{
			return null;
		}
	}

	private static String getValueNameMapAsString(Map<Integer, String> map){
		String str = "";
		for(Map.Entry<Integer, String> entry : map.entrySet()){
			Integer value = entry.getKey();
			String name = entry.getValue();
			str += name + "("+value+"), ";
		}
		// Remove the trailing ', '
		str = str.length() > 0 ? str.substring(0, str.length() - 2) : str;
		return str;
	}

	private boolean isUdp(ArtifactIdentifier identifier){
		if(identifier != null && identifier.getClass().equals(NetworkSocketIdentifier.class)){
			if(PROTOCOL_NAME_UDP.equals(((NetworkSocketIdentifier)identifier).getProtocol())){
				return true;
			}
		}
		return false;
	}
	
	public static Integer getProtocolNumber(String protocolName){
		if(PROTOCOL_NAME_UDP.equals(protocolName)){
			return 17;
		}else if(PROTOCOL_NAME_TCP.equals(protocolName)){
			return 6;
		}else{
			return null;
		}
	}
	
	private static String getProtocolName(Integer protocolNumber){
		if(protocolNumber != null){
			if(protocolNumber == 17){
				return PROTOCOL_NAME_UDP;
			}else if(protocolNumber == 6){
				return PROTOCOL_NAME_TCP;
			}
		}
		return null;
	}
	

	private void logInvalidSaddr(String saddr, String time, String eventId, SYSCALL syscall){
		if(!"0100".equals(saddr)){ // if not empty path
			log(Level.INFO, "Failed to parse saddr: " + saddr, null, time, eventId, syscall);
		}
	}
	
	private String getProtocolNameBySockType(Integer sockType){
		if(sockType != null){
			if((sockType & SOCK_SEQPACKET) == SOCK_SEQPACKET){ // check first because seqpacket matches stream too
				return PROTOCOL_NAME_TCP;
			}else if((sockType & SOCK_STREAM) == SOCK_STREAM){
				return PROTOCOL_NAME_TCP;
			}else if((sockType & SOCK_DGRAM) == SOCK_DGRAM){
				return PROTOCOL_NAME_UDP;
			}
		}
		return null;
	}
	
	/**
	 * Groups system call names by functionality and returns that name to simplify identification of the type of system call.
	 * Grouping only done if {@link #SIMPLIFY SIMPLIFY} is true otherwise the system call name is returned simply.
	 * 
	 * @param syscall system call to get operation for
	 * @return operation corresponding to the syscall
	 */
	public String getOperation(SYSCALL primary){
		return getOperation(primary, null);
	}
	
	private String getOperation(SYSCALL primary, SYSCALL secondary){
		return OPMConstants.getOperation(primary, secondary, SIMPLIFY);
	}

}

class AddressPort{
	public final String address, port;
	public AddressPort(String address, String port){
		this.address = address;
		this.port = port;
	}
	@Override
	public int hashCode() {
		final int prime = 31;
		int result = 1;
		result = prime * result + ((address == null) ? 0 : address.hashCode());
		result = prime * result + ((port == null) ? 0 : port.hashCode());
		return result;
	}
	@Override
	public boolean equals(Object obj) {
		if (this == obj)
			return true;
		if (obj == null)
			return false;
		if (getClass() != obj.getClass())
			return false;
		AddressPort other = (AddressPort) obj;
		if (address == null) {
			if (other.address != null)
				return false;
		} else if (!address.equals(other.address))
			return false;
		if (port == null) {
			if (other.port != null)
				return false;
		} else if (!port.equals(other.port))
			return false;
		return true;
	}
}
