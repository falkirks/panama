/*
 --------------------------------------------------------------------------------
 SPADE - Support for Provenance Auditing in Distributed Environments.
 Copyright (C) 2020 SRI International
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
package spade.transformer;

import static spade.core.Kernel.FILE_SEPARATOR;

import java.io.BufferedReader;
import java.io.File;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Calendar;
import java.util.Date;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.logging.Level;
import java.util.logging.Logger;

import javax.crypto.Cipher;
import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;
import javax.crypto.spec.SecretKeySpec;

import org.apache.commons.codec.Charsets;
import org.apache.commons.codec.binary.Hex;
import org.apache.commons.collections.CollectionUtils;
import org.apache.commons.io.FileUtils;

import spade.client.QueryMetaData;
import spade.core.AbstractEdge;
import spade.core.AbstractTransformer;
import spade.core.AbstractVertex;
import spade.core.Graph;
import spade.core.Settings;
import spade.reporter.audit.OPMConstants;
import spade.utility.ABEGraph;
import spade.utility.HelperFunctions;

public class ABE extends AbstractTransformer
{
	private String encryptionLevel;
	private String decryptionLevel;
	private KeyGenerator keyGenerator;
	private Ciphers cipher;
	private static final String LOW = "low";
	private static final String MEDIUM = "medium";
	private static final String HIGH = "high";
	private static final String BASE_ALGORITHM = "AES";
	private static final String ENCRYPTION_LEVEL = "encryptionLevel";
	private static final String DECRYPTION_LEVEL = "decryptionLevel";
	private static final String DECRYPTION_KEY_FILE_NAME = "decryptionKeyFileName";
	private static final String KEYS_DIRECTORY = "keysDirectory";
	private File decryptionKeyFile;
	private File KEYS_DIR;
	private static final String EDGE = "Edge";
	private static final String ALGORITHM = "AES/ECB/PKCS5Padding";
	private static Logger logger = Logger.getLogger(ABE.class.getName());

	private Map<String, List<String>> lowMap = new HashMap<>();
	private Map<String, List<String>> mediumMap = new HashMap<>();
	private Map<String, List<String>> highMap = new HashMap<>();

	private static class SecretKeys
	{
		SecretKey low;
		SecretKey medium;
		SecretKey high;
	}

	private static class Ciphers
	{
		Cipher low;
		Cipher medium;
		Cipher high;
	}

	@Override
	public boolean initialize(String arguments)
	{
		boolean configFileStatus = readConfigFile();
		if(!configFileStatus)
		{
			return false;
		}
		Map<String, String> argsMap = HelperFunctions.parseKeyValPairs(arguments);
		String level_str = argsMap.get("encryptionLevel");
		if(level_str != null)
		{
			this.encryptionLevel = level_str;
		}

		try
		{
			// create key generator
			this.keyGenerator = KeyGenerator.getInstance(BASE_ALGORITHM);
			this.keyGenerator.init(128);
			this.cipher = new Ciphers();
			this.cipher.low = Cipher.getInstance(ALGORITHM);
			this.cipher.medium = Cipher.getInstance(ALGORITHM);
			this.cipher.high = Cipher.getInstance(ALGORITHM);
		}
		catch(Exception ex)
		{
			logger.log(Level.SEVERE, "Error getting instance of key generator or cipher", ex);
			return false;
		}
		return true;
	}

	private boolean readConfigFile()
	{
		String configFileName = Settings.getDefaultConfigFilePath(this.getClass());
		// read config file here and set encryption level
		try
		{
			String keyDirectoryPath = null;
			String decryptionKeyFileName = null;
			String level = null;
			List<String> lines = FileUtils.readLines(new File(configFileName), Charsets.UTF_8);
			for(String line : lines)
			{
				line = line.trim();
				if(!HelperFunctions.isNullOrEmpty(line) && !line.startsWith("#"))
				{
					if(line.equalsIgnoreCase(LOW))
					{
						level = LOW;
					}
					else if(line.equalsIgnoreCase(MEDIUM))
					{
						level = MEDIUM;
					}
					else if(line.equalsIgnoreCase(HIGH))
					{
						level = HIGH;
					}
					else
					{
						String[] split = line.split("=");
						if(split.length != 2)
						{
							String msg = "Incorrect config file formatting in line '" + line + "'";
							logger.log(Level.WARNING, msg);
							continue;
						}
						String type = split[0].trim();
						String value = split[1].trim();
						switch(type)
						{
							case ENCRYPTION_LEVEL:
							{
								encryptionLevel = value;
							}
							break;
							case DECRYPTION_LEVEL:
							{
								decryptionLevel = value;
							}
							;
							break;
							case DECRYPTION_KEY_FILE_NAME:
							{
								decryptionKeyFileName = value;
							}
							;
							break;
							case KEYS_DIRECTORY:
							{
								keyDirectoryPath = value;
							}
							;
							break;
							default:
							{
								if(level == null)
								{
									logger.log(Level.WARNING, "Unexpected key '" + type + "'");
									continue;
								}
								else
								{
									String[] annotations = value.split(",");
									switch(level)
									{
										case LOW:
											lowMap.put(type, Arrays.asList(annotations));
											break;
										case MEDIUM:
											mediumMap.put(type, Arrays.asList(annotations));
											break;
										case HIGH:
											highMap.put(type, Arrays.asList(annotations));
											break;
									}
								}
							}
							;
							break;
						}

					}
				}
			}

			if(keyDirectoryPath == null)
			{
				logger.log(Level.SEVERE, "NULL '" + KEYS_DIRECTORY + "'");
				return false;
			}
			else
			{
				if(decryptionKeyFileName == null)
				{
					logger.log(Level.SEVERE, "NULL '" + DECRYPTION_KEY_FILE_NAME + "'");
					return false;
				}
				else
				{
					File keyDirFile = new File(keyDirectoryPath);
					File decryptFile = new File(keyDirectoryPath + FILE_SEPARATOR + decryptionKeyFileName);
					try
					{
						if(keyDirFile.isDirectory())
						{
							try
							{
								if(decryptFile.isFile())
								{
									this.KEYS_DIR = keyDirFile;
									this.decryptionKeyFile = decryptFile;
									if(HelperFunctions.isNullOrEmpty(encryptionLevel))
									{
										logger.log(Level.SEVERE, "NULL '" + ENCRYPTION_LEVEL + "'");
										return false;
									}
									else if(HelperFunctions.isNullOrEmpty(decryptionLevel))
									{
										logger.log(Level.SEVERE, "NULL '" + DECRYPTION_LEVEL + "'");
										return false;
									}
									else
									{
										logger.log(Level.INFO, String.format("Arguments: %s=%s, %s=%s, %s=%s, %s=%s",
												KEYS_DIRECTORY, keyDirectoryPath,
												DECRYPTION_KEY_FILE_NAME, decryptionKeyFileName,
												ENCRYPTION_LEVEL, encryptionLevel,
												DECRYPTION_LEVEL, decryptionLevel));
										return true;
									}
								}
								else
								{
									logger.log(Level.SEVERE, "'" + DECRYPTION_KEY_FILE_NAME + "' must be a file");
									return false;
								}
							}
							catch(Exception e)
							{
								logger.log(Level.SEVERE, "Failed to check if '" + DECRYPTION_KEY_FILE_NAME + "' is a file", e);
								return false;
							}
						}
						else
						{
							logger.log(Level.SEVERE, "'" + KEYS_DIRECTORY + "' must be a directory");
							return false;
						}
					}
					catch(Exception e)
					{
						logger.log(Level.SEVERE, "Failed to check if '" + KEYS_DIRECTORY + "' is a directory", e);
						return false;
					}
				}
			}
		}
		catch(Exception ex)
		{
			logger.log(Level.SEVERE, "Unable to read config file properly", ex);
			return false;
		}
	}

	private SecretKey generateKey()
	{
		return keyGenerator.generateKey();
	}

	private SecretKeys generateSymmetricKeys()
	{
		SecretKeys secretKeys = new SecretKeys();
		secretKeys.low = generateKey();
		secretKeys.medium = generateKey();
		secretKeys.high = generateKey();
		if(secretKeys.low == null || secretKeys.medium == null || secretKeys.high == null)
		{
			logger.log(Level.SEVERE, "Symmetric keys not generated properly");
			return null;
		}
		return secretKeys;
	}

	private String decryptAnnotation(String encryptedAnnotation, Cipher cipher)
	{
		if(encryptedAnnotation == null)
			return null;
		try
		{
			byte[] decryptedAnnotation = cipher.doFinal(Hex.decodeHex(encryptedAnnotation.toCharArray()));
			return new String(decryptedAnnotation, StandardCharsets.UTF_8);
		}
		catch(Exception ex)
		{
			String message = "Unable to decrypt annotation " + "'" + encryptedAnnotation + "'.";
			logger.log(Level.SEVERE, message, ex);
		}
		return null;
	}

	private ABEGraph decryptGraph(ABEGraph graph, SecretKeys secretKeys, String decryptionLevel)
	{
		ABEGraph decryptedGraph = ABEGraph.copy(graph);
		try
		{
			// initialize ciphers for decryption
			Ciphers ciphers = new Ciphers();
			switch(decryptionLevel)
			{
				case HIGH:
					ciphers.high = Cipher.getInstance(ALGORITHM);
					ciphers.high.init(Cipher.DECRYPT_MODE, secretKeys.high);
				case MEDIUM:
					ciphers.medium = Cipher.getInstance(ALGORITHM);
					ciphers.medium.init(Cipher.DECRYPT_MODE, secretKeys.medium);
				case LOW:
					ciphers.low = Cipher.getInstance(ALGORITHM);
					ciphers.low.init(Cipher.DECRYPT_MODE, secretKeys.low);
			}

			String encryptedAnnotation;
			String decryptedAnnotation;
			for(AbstractVertex vertex : decryptedGraph.vertexSet())
			{
				switch(vertex.type())
				{
					case OPMConstants.PROCESS:
						switch(decryptionLevel)
						{
							case HIGH:
								// decrypt name
								encryptedAnnotation = vertex.getAnnotation(OPMConstants.PROCESS_NAME);
								decryptedAnnotation = decryptAnnotation(encryptedAnnotation, ciphers.high);
								vertex.addAnnotation(OPMConstants.PROCESS_NAME, decryptedAnnotation);
							case MEDIUM:
								// decrypt command line
								encryptedAnnotation = vertex.getAnnotation(OPMConstants.PROCESS_COMMAND_LINE);
								if(encryptedAnnotation != null)
								{
									decryptedAnnotation = decryptAnnotation(encryptedAnnotation, ciphers.medium);
									vertex.addAnnotation(OPMConstants.PROCESS_COMMAND_LINE, decryptedAnnotation);
								}
							case LOW:
								// decrypt cwd
								encryptedAnnotation = vertex.getAnnotation(OPMConstants.PROCESS_CWD);
								if(encryptedAnnotation != null)
								{
									decryptedAnnotation = decryptAnnotation(encryptedAnnotation, ciphers.low);
									vertex.addAnnotation(OPMConstants.PROCESS_CWD, decryptedAnnotation);
								}
						}
						break;
					case OPMConstants.AGENT:
						switch(decryptionLevel)
						{
							case HIGH:
								// decrypt euid
								encryptedAnnotation = vertex.getAnnotation(OPMConstants.AGENT_EUID);
								decryptedAnnotation = decryptAnnotation(encryptedAnnotation, ciphers.high);
								vertex.addAnnotation(OPMConstants.AGENT_EUID, decryptedAnnotation);
							case MEDIUM:
								// decrypt uid
								encryptedAnnotation = vertex.getAnnotation(OPMConstants.AGENT_UID);
								decryptedAnnotation = decryptAnnotation(encryptedAnnotation, ciphers.medium);
								vertex.addAnnotation(OPMConstants.AGENT_UID, decryptedAnnotation);

								// decrypt gid
								encryptedAnnotation = vertex.getAnnotation(OPMConstants.AGENT_GID);
								decryptedAnnotation = decryptAnnotation(encryptedAnnotation, ciphers.medium);
								vertex.addAnnotation(OPMConstants.AGENT_GID, decryptedAnnotation);
							case LOW:
								// decrypt fsgid
								encryptedAnnotation = vertex.getAnnotation(OPMConstants.AGENT_FSGID);
								decryptedAnnotation = decryptAnnotation(encryptedAnnotation, ciphers.low);
								vertex.addAnnotation(OPMConstants.AGENT_FSGID, decryptedAnnotation);

								// decrypt fsuid
								encryptedAnnotation = vertex.getAnnotation(OPMConstants.AGENT_FSUID);
								decryptedAnnotation = decryptAnnotation(encryptedAnnotation, ciphers.low);
								vertex.addAnnotation(OPMConstants.AGENT_FSUID, decryptedAnnotation);

								//decrypt sgid
								encryptedAnnotation = vertex.getAnnotation(OPMConstants.AGENT_SGID);
								decryptedAnnotation = decryptAnnotation(encryptedAnnotation, ciphers.low);
								vertex.addAnnotation(OPMConstants.AGENT_SGID, decryptedAnnotation);

								// decrypt suid
								encryptedAnnotation = vertex.getAnnotation(OPMConstants.AGENT_SUID);
								decryptedAnnotation = decryptAnnotation(encryptedAnnotation, ciphers.low);
								vertex.addAnnotation(OPMConstants.AGENT_SUID, decryptedAnnotation);
						}
						break;
					case OPMConstants.ARTIFACT:
						// decrypt remote address
						encryptedAnnotation = vertex.getAnnotation(OPMConstants.ARTIFACT_REMOTE_ADDRESS);
						if(encryptedAnnotation != null)
						{
							String[] subnets = encryptedAnnotation.split("\\.");
							switch(decryptionLevel)
							{
								case HIGH:
									subnets[1] = decryptAnnotation(subnets[1], ciphers.high);
								case MEDIUM:
									subnets[2] = decryptAnnotation(subnets[2], ciphers.medium);
								case LOW:
									subnets[3] = decryptAnnotation(subnets[3], ciphers.low);
									decryptedAnnotation = String.join(".", subnets);
									vertex.addAnnotation(OPMConstants.ARTIFACT_REMOTE_ADDRESS, decryptedAnnotation);
							}
						}

						// decrypt path
						encryptedAnnotation = vertex.getAnnotation(OPMConstants.ARTIFACT_PATH);
						if(encryptedAnnotation != null)
						{
							String[] subpaths = encryptedAnnotation.split(FILE_SEPARATOR, 5);
							int numpaths = subpaths.length;
							switch(decryptionLevel)
							{
								case HIGH:
									if(numpaths > 2)
									{
										subpaths[2] = decryptAnnotation(subpaths[2], ciphers.high);
									}
								case MEDIUM:
									if(numpaths > 3)
									{
										subpaths[3] = decryptAnnotation(subpaths[3], ciphers.medium);
									}
								case LOW:
									if(numpaths > 4)
									{
										subpaths[4] = decryptAnnotation(subpaths[4], ciphers.low);
									}
									decryptedAnnotation = String.join(FILE_SEPARATOR, subpaths);
									vertex.addAnnotation(OPMConstants.ARTIFACT_PATH, decryptedAnnotation);
							}
						}
						break;
				}
			}

			for(AbstractEdge edge : decryptedGraph.edgeSet())
			{
				// parse individual units of time the timestamp
				// time format is 'yyyy-MM-dd HH:mm:ss.SSS'
				String time = edge.getAnnotation(OPMConstants.EDGE_TIME);
				String regex = ":|-|\\.| ";
				String[] split = time.split(regex);
				String year = split[0];
				String month = split[1];
				String day = split[2];
				String hour = split[3];
				String minute = split[4];
				String second = split[5];
				String millisecond = split[6];

				switch(decryptionLevel)
				{
					case HIGH:
						// decrypt time
						day = decryptAnnotation(day, ciphers.high);

						// decrypt operation
						encryptedAnnotation = edge.getAnnotation(OPMConstants.EDGE_OPERATION);
						decryptedAnnotation = decryptAnnotation(encryptedAnnotation, ciphers.high);
						edge.addAnnotation(OPMConstants.EDGE_OPERATION, decryptedAnnotation);
					case MEDIUM:
						// decrypt time
						hour = decryptAnnotation(hour, ciphers.medium);

						// decrypt size
						encryptedAnnotation = edge.getAnnotation(OPMConstants.EDGE_SIZE);
						if(encryptedAnnotation != null)
						{
							decryptedAnnotation = decryptAnnotation(encryptedAnnotation, ciphers.medium);
							edge.addAnnotation(OPMConstants.EDGE_SIZE, decryptedAnnotation);
						}
					case LOW:
						// decrypt time
						minute = decryptAnnotation(minute, ciphers.low);
						second = decryptAnnotation(second, ciphers.low);
						millisecond = decryptAnnotation(millisecond, ciphers.low);

						// stitch time with format is 'yyyy-MM-dd HH:mm:ss.SSS'
						String timestamp = year + "-" + month + "-" + day + " " + hour + ":" +
								minute + ":" + second + "." + millisecond;
						edge.addAnnotation(OPMConstants.EDGE_TIME, timestamp);
				}
			}
			return decryptedGraph;
		}
		catch(Exception ex)
		{
			logger.log(Level.SEVERE, "Unable to initialize ciphers for decryption!", ex);
			return null;
		}
	}

	private String getAttributeKeyName()
	{
		return "medium.key";
	}

	private SecretKey decryptKey(String encryptedKey)
	{
		try
		{
			// write encrypted key to a file temporarily
			String encryptedKeyFileName = "key.cpabe";
			File encryptedKeyFile = new File(KEYS_DIR.getAbsolutePath() + FILE_SEPARATOR + encryptedKeyFileName);
			FileUtils.writeStringToFile(encryptedKeyFile, encryptedKey, StandardCharsets.UTF_8);

			// perform ABE decryption
			String keyFileName = "key.txt";
			//TODO: decryptionLevel is not user specific. Use the key stored for this user here
			String command = "oabe_dec -s CP -p spade -k " + decryptionKeyFile.getAbsolutePath() + " -i " + encryptedKeyFileName +
					" -o " + keyFileName;
			Runtime runtime = Runtime.getRuntime();
			Process process = runtime.exec(command, null, KEYS_DIR);
			process.waitFor();
			encryptedKeyFile.delete();

			// check for errors
			BufferedReader stdError = new BufferedReader(new InputStreamReader(process.getErrorStream()));
			if(process.exitValue() != 0)
			{
				logger.log(Level.SEVERE, "Key decryption not successful! " +
						"Here are the errors...");
				String errors;
				while((errors = stdError.readLine()) != null)
				{
					logger.log(Level.SEVERE, errors);
				}
				return null;
			}

			// read decrypted key from file
			File keyFile = new File(KEYS_DIR.getAbsolutePath() + FILE_SEPARATOR + keyFileName);
			String decryptedKey = FileUtils.readFileToString(keyFile, StandardCharsets.UTF_8);
			keyFile.delete();

			// decode and reconstruct
			byte[] secretKeyBytes = Hex.decodeHex(decryptedKey.toCharArray());
			return new SecretKeySpec(secretKeyBytes, 0, secretKeyBytes.length, BASE_ALGORITHM);
		}
		catch(Exception ex)
		{
			logger.log(Level.SEVERE,
					"Error decrypting symmetric key ", ex);
			return null;
		}
	}

	private SecretKeys decryptSymmetricKeys(ABEGraph graph, String decryptionLevel)
	{
		SecretKeys secretKeys = new SecretKeys();
		switch(decryptionLevel)
		{
			case HIGH:
				secretKeys.high = decryptKey(graph.getHighKey());
			case MEDIUM:
				secretKeys.medium = decryptKey(graph.getMediumKey());
			case LOW:
				secretKeys.low = decryptKey(graph.getLowKey());
		}
		return secretKeys;
	}

	public ABEGraph decryptGraph(ABEGraph graph)
	{
		String level_str = graph.getLevel();
		SecretKeys secretKeys = decryptSymmetricKeys(graph, level_str);
		return decryptGraph(graph, secretKeys, level_str);
	}

	private String encryptAnnotation(String plainAnnotation, Cipher cipher)
	{
		if(plainAnnotation == null)
			return null;
		try
		{
			String encryptedAnnotationStr;
			byte[] encryptedAnnotation = cipher.doFinal(plainAnnotation.getBytes(StandardCharsets.UTF_8));
			encryptedAnnotationStr = Hex.encodeHexString(encryptedAnnotation);
			return encryptedAnnotationStr;
		}
		catch(Exception ex)
		{
			String message = "Unable to encrypt annotation " + "'" + plainAnnotation + "'. " +
					"This would disturb any further encryption of annotations.";
			logger.log(Level.WARNING, message, ex);
			return null;
		}
	}

	private String encryptKey(SecretKey secretKey, String level)
	{
		try
		{
			// write secret key to a file temporarily
			String keyFileName = "key.txt";
			String encryptedKeyFileName = "key.cpabe";
			String encodedKey = Hex.encodeHexString(secretKey.getEncoded());
			File keyFile = new File(KEYS_DIR.getAbsolutePath() + FILE_SEPARATOR + keyFileName);
			FileUtils.writeStringToFile(keyFile, encodedKey, StandardCharsets.UTF_8);

			// perform ABE encryption
			String command = "oabe_enc -s CP -p spade -e (" + level + ") -i " + keyFileName +
					" -o " + encryptedKeyFileName;
			Runtime runtime = Runtime.getRuntime();
			Process process = runtime.exec(command, null, KEYS_DIR);
			process.waitFor();
			keyFile.delete();

			// check for errors
			BufferedReader stdError = new BufferedReader(new InputStreamReader(process.getErrorStream()));
			if(process.exitValue() != 0)
			{
				logger.log(Level.SEVERE, "Encryption of " + level + " key not successful! " +
						"Here are the errors...");
				String errors;
				while((errors = stdError.readLine()) != null)
				{
					logger.log(Level.SEVERE, errors);
				}
				return null;
			}

			// read encrypted key from file
			File encryptedKeyFile = new File(KEYS_DIR.getAbsolutePath() + FILE_SEPARATOR + encryptedKeyFileName);
			String encryptedKey = FileUtils.readFileToString(encryptedKeyFile, StandardCharsets.UTF_8);
			encryptedKeyFile.delete();

			return encryptedKey;
		}
		catch(Exception ex)
		{
			logger.log(Level.SEVERE, "Error encrypting " + level + " symmetric key", ex);
			return null;
		}
	}

	// encrypt the symmetric keys as per ABE
	private void encryptSymmetricKeys(SecretKeys secretKeys, ABEGraph graph)
	{
		String low = encryptKey(secretKeys.low, this.encryptionLevel);
		graph.setLowKey(low);

		String medium = encryptKey(secretKeys.medium, this.encryptionLevel);
		graph.setMediumKey(medium);

		String high = encryptKey(secretKeys.high, this.encryptionLevel);
		graph.setHighKey(high);
	}

	private void encryptAnnotations(AbstractVertex vertex, List<String> annotations, Cipher cipher)
	{
		for(String annotation : annotations)
		{
			String plainAnnotation = vertex.getAnnotation(annotation);
			String encryptedAnnotation;
			if(plainAnnotation != null)
			{
				encryptedAnnotation = encryptAnnotation(plainAnnotation, cipher);
				vertex.addAnnotation(annotation, encryptedAnnotation);
			}
		}
	}

	private void encryptAnnotations(AbstractEdge edge, List<String> annotations, Cipher cipher)
	{
		for(String annotation : annotations)
		{
			String plainAnnotation = edge.getAnnotation(annotation);
			String encryptedAnnotation;
			if(plainAnnotation != null)
			{
				encryptedAnnotation = encryptAnnotation(plainAnnotation, cipher);
				edge.addAnnotation(annotation, encryptedAnnotation);
			}
		}
	}

	@Override
	public ABEGraph transform(Graph graph, QueryMetaData queryMetaData)
	{
		ABEGraph encryptedGraph = ABEGraph.copy(graph);
		Set<AbstractVertex> endPoints = new HashSet<>();
		for(AbstractEdge edge : encryptedGraph.edgeSet())
		{
			endPoints.add(edge.getChildVertex());
			endPoints.add(edge.getParentVertex());
		}
		endPoints.addAll(encryptedGraph.vertexSet());

		// generate 3 symmetric keys
		SecretKeys secretKeys = generateSymmetricKeys();
		if(secretKeys == null)
		{
			logger.log(Level.SEVERE, "Unable to encrypt data");
			return null;
		}
		try
		{
			// encrypt data
			this.cipher.low.init(Cipher.ENCRYPT_MODE, secretKeys.low);
			this.cipher.medium.init(Cipher.ENCRYPT_MODE, secretKeys.medium);
			this.cipher.high.init(Cipher.ENCRYPT_MODE, secretKeys.high);
		}
		catch(Exception ex)
		{
			logger.log(Level.SEVERE, "Unable to initialize ciphers for encryption!");
		}

		transformVertices(endPoints);
		transformEdges(encryptedGraph.edgeSet());

		// encrypt the symmetric keys as per ABE
		encryptSymmetricKeys(secretKeys, encryptedGraph);
		encryptedGraph.setLevel(encryptionLevel);
		return encryptedGraph;
	}

	private void transformEdges(Set<AbstractEdge> edgeSet)
	{
		List<String> highAnnotations;
		List<String> mediumAnnotations;
		List<String> lowAnnotations;
		List<String> commonAnnotations;
		highAnnotations = highMap.get(EDGE);
		mediumAnnotations = mediumMap.get(EDGE);
		lowAnnotations = lowMap.get(EDGE);
		commonAnnotations = (List<String>) CollectionUtils.intersection(highAnnotations, mediumAnnotations);
		commonAnnotations = (List<String>) CollectionUtils.intersection(commonAnnotations, lowAnnotations);
		highAnnotations = (List<String>) CollectionUtils.disjunction(commonAnnotations, highAnnotations);
		mediumAnnotations = (List<String>) CollectionUtils.disjunction(commonAnnotations, mediumAnnotations);
		lowAnnotations = (List<String>) CollectionUtils.disjunction(commonAnnotations, lowAnnotations);

		for(AbstractEdge edge : edgeSet)
		{
			// encrypt non-common annotations here
			switch(encryptionLevel)
			{
				case HIGH:
					encryptAnnotations(edge, highAnnotations, this.cipher.high);
				case MEDIUM:
					encryptAnnotations(edge, mediumAnnotations, this.cipher.medium);
				case LOW:
					encryptAnnotations(edge, lowAnnotations, this.cipher.low);
			}

			// encrypt common annotations here
			for(String annotation : commonAnnotations)
			{
				if(annotation.equals(OPMConstants.EDGE_TIME))
				{
					// extract time details from unix time
					String time = edge.getAnnotation(OPMConstants.EDGE_TIME);
					Date date = new Date(Double.valueOf(Double.parseDouble(time) * 1000).longValue());
					Calendar calendar = Calendar.getInstance();
					calendar.setTime(date);
					String year = String.valueOf(calendar.get(Calendar.YEAR));
					String month = String.valueOf(calendar.get(Calendar.MONTH) + 1); // zero-based indexing
					String day = String.valueOf(calendar.get(Calendar.DAY_OF_MONTH));
					String hour = String.valueOf(calendar.get(Calendar.HOUR_OF_DAY));
					String minute = String.valueOf(calendar.get(Calendar.MINUTE));
					String second = String.valueOf(calendar.get(Calendar.SECOND));
					String millisecond = String.valueOf(calendar.get(Calendar.MILLISECOND));

					switch(encryptionLevel)
					{
						case HIGH:
							// encrypt time
							day = encryptAnnotation(day, this.cipher.high);

						case MEDIUM:
							// encrypt time
							hour = encryptAnnotation(hour, this.cipher.medium);

						case LOW:
							// encrypt time
							minute = encryptAnnotation(minute, this.cipher.low);
							second = encryptAnnotation(second, this.cipher.low);
							millisecond = encryptAnnotation(millisecond, this.cipher.low);

							// stitch time with format is 'yyyy-MM-dd HH:mm:ss.SSS'
							String timestamp = year + "-" + month + "-" + day + " " + hour + ":" +
									minute + ":" + second + "." + millisecond;
							edge.addAnnotation(OPMConstants.EDGE_TIME, timestamp);
					}
				}
			}
		}
	}

	private void transformVertex(AbstractVertex vertex)
	{
		String plainAnnotation;
		String encryptedAnnotation;
		List<String> highAnnotations;
		List<String> mediumAnnotations;
		List<String> lowAnnotations;
		List<String> commonAnnotations;
		switch(vertex.type())
		{
			case OPMConstants.PROCESS:
				highAnnotations = highMap.get(OPMConstants.PROCESS);
				mediumAnnotations = mediumMap.get(OPMConstants.PROCESS);
				lowAnnotations = lowMap.get(OPMConstants.PROCESS);

				// encrypt common annotations here. None for now

				switch(encryptionLevel)
				{
					case HIGH:
						encryptAnnotations(vertex, highAnnotations, this.cipher.high);
					case MEDIUM:
						encryptAnnotations(vertex, mediumAnnotations, this.cipher.medium);
					case LOW:
						encryptAnnotations(vertex, lowAnnotations, this.cipher.low);
				}
				break;

			case OPMConstants.AGENT:
				highAnnotations = highMap.get(OPMConstants.AGENT);
				mediumAnnotations = mediumMap.get(OPMConstants.AGENT);
				lowAnnotations = lowMap.get(OPMConstants.AGENT);
				switch(encryptionLevel)
				{
					case HIGH:
						encryptAnnotations(vertex, highAnnotations, this.cipher.high);
					case MEDIUM:
						encryptAnnotations(vertex, mediumAnnotations, this.cipher.medium);
					case LOW:
						encryptAnnotations(vertex, lowAnnotations, this.cipher.low);
				}
				break;

			case OPMConstants.ARTIFACT:
				highAnnotations = highMap.get(OPMConstants.ARTIFACT);
				mediumAnnotations = mediumMap.get(OPMConstants.ARTIFACT);
				lowAnnotations = lowMap.get(OPMConstants.ARTIFACT);
				commonAnnotations = (List<String>) CollectionUtils.intersection(highAnnotations, mediumAnnotations);
				commonAnnotations = (List<String>) CollectionUtils.intersection(commonAnnotations, lowAnnotations);

				// encrypt non-common annotations here. None for now

				for(String annotation : commonAnnotations)
				{
					plainAnnotation = vertex.getAnnotation(annotation);
					if(plainAnnotation != null)
					{
						if(annotation.equals(OPMConstants.ARTIFACT_REMOTE_ADDRESS))
						{
							String[] subnets = plainAnnotation.split("\\.");
							switch(encryptionLevel)
							{
								case HIGH:
									subnets[1] = encryptAnnotation(subnets[1], this.cipher.high);

								case MEDIUM:
									subnets[2] = encryptAnnotation(subnets[2], this.cipher.medium);

								case LOW:
									subnets[3] = encryptAnnotation(subnets[3], this.cipher.low);
									encryptedAnnotation = String.join(".", subnets);
									vertex.addAnnotation(annotation, encryptedAnnotation);
							}
						}
						else if(annotation.equals(OPMConstants.ARTIFACT_PATH))
						{
							String[] subpaths = plainAnnotation.split(FILE_SEPARATOR, 5);
							int numpaths = subpaths.length;
							switch(encryptionLevel)
							{
								case HIGH:
									if(numpaths > 2)
									{
										subpaths[2] = encryptAnnotation(subpaths[2], this.cipher.high);
									}
								case MEDIUM:
									if(numpaths > 3)
									{
										subpaths[3] = encryptAnnotation(subpaths[3], this.cipher.medium);
									}
								case LOW:
									if(numpaths > 4)
									{
										subpaths[4] = encryptAnnotation(subpaths[4], this.cipher.low);
									}
									encryptedAnnotation = String.join(FILE_SEPARATOR, subpaths);
									vertex.addAnnotation(OPMConstants.ARTIFACT_PATH, encryptedAnnotation);
							}
						}
					}
				}
				break;
		}
	}

	private void transformVertices(Set<AbstractVertex> vertexSet)
	{
		for(AbstractVertex vertex : vertexSet)
		{
			transformVertex(vertex);
		}
	}

//	public static void main(String[] args)
//	{
//		Graph graph = Graph.importGraph("sample.dot");
//		System.out.println(graph);
//		ABE abe = new ABE();
//		abe.initialize("encryptionLevel=medium");
//		ABEGraph encryptedGraph = abe.transform(graph, null);
//		System.out.println(encryptedGraph);
//		encryptedGraph.setLevel("medium");
//
//		ABEGraph decryptedGraph = abe.decryptGraph(encryptedGraph);
//		System.out.println(decryptedGraph);
//		System.out.println(decryptedGraph.equals(graph));
//		System.out.println(decryptedGraph.toString().equals(graph.toString()));
//	}
}
