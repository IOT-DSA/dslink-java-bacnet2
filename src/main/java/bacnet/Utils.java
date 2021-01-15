package bacnet;

import com.serotonin.bacnet4j.RemoteDevice;
import com.serotonin.bacnet4j.ServiceFuture;
import com.serotonin.bacnet4j.exception.BACnetException;
import com.serotonin.bacnet4j.npdu.ip.IpNetworkUtils;
import com.serotonin.bacnet4j.service.confirmed.ConfirmedRequestService;
import com.serotonin.bacnet4j.type.Encodable;
import com.serotonin.bacnet4j.type.constructed.Address;
import com.serotonin.bacnet4j.type.constructed.LogRecord;
import com.serotonin.bacnet4j.type.enumerated.ObjectType;
import com.serotonin.bacnet4j.type.enumerated.PropertyIdentifier;
import com.serotonin.bacnet4j.type.primitive.Enumerated;
import com.serotonin.bacnet4j.type.primitive.ObjectIdentifier;
import com.serotonin.bacnet4j.type.primitive.OctetString;
import com.serotonin.bacnet4j.type.primitive.UnsignedInteger;
import com.serotonin.bacnet4j.util.BACnetUtils;
import com.serotonin.bacnet4j.util.RequestUtils;
import java.lang.reflect.InvocationTargetException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Set;
import java.util.regex.Pattern;
import jssc.SerialNativeInterface;
import jssc.SerialPortList;
import org.dsa.iot.dslink.node.Node;
import org.dsa.iot.dslink.node.actions.ActionResult;
import org.dsa.iot.dslink.node.value.Value;
import org.dsa.iot.dslink.node.value.ValueType;
import org.dsa.iot.dslink.node.value.ValueUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class Utils {

	private static final Logger LOGGER = LoggerFactory.getLogger(Utils.class);

	private static final Map<Class<? extends Enumerated>, List<String>> stateLists = new HashMap<>();

	static {
		getObjectTypeList();
		getPropertyList();
	}

	// private static final List<String> objectTypeList;
	// static {
	// objectTypeList = new ArrayList<String>();
	// for (int i = 0; i < ObjectType.size(); i++) {
	// String s = ObjectType.nameForId(i);
	// if (s != null) {
	// objectTypeList.add(s);
	// }
	// }
	// }
	// private static final List<String> propertyList;
	// static {
	// propertyList = new ArrayList<String>();
	// for (int i = 0; i < PropertyIdentifier.size(); i++) {
	// String s = PropertyIdentifier.nameForId(i);
	// if (s != null) {
	// propertyList.add(s);
	// }
	// }
	// }

	public static ServiceFuture sendConfirmedRequest(BacnetConn conn, BacnetDevice device, ConfirmedRequestService request) {
		ServiceFuture sf = null;
		try {
			device.monitor.checkInReader();
			if (device.remoteDevice != null) {
				try {
					conn.monitor.checkInReader();
					if (conn.localDevice != null) {
						sf = conn.localDevice.send(device.remoteDevice, request);
					}
					conn.monitor.checkOutReader();
				} catch (InterruptedException ignored) {

				}
			}
			device.monitor.checkOutReader();
		} catch (InterruptedException ignored) {

		}
		return sf;
	}
	
	public static Encodable readProperty(BacnetConn conn, BacnetDevice device, ObjectIdentifier oid, PropertyIdentifier pid, UnsignedInteger propertyArrayIndex) {
		Encodable enc = null;
		try {
			device.monitor.checkInReader();
			if (device.remoteDevice != null) {
				try {
					conn.monitor.checkInReader();
					if (conn.localDevice != null) {
						try {
							enc = RequestUtils.sendReadPropertyAllowNull(conn.localDevice, device.remoteDevice, oid, pid, propertyArrayIndex, null);
						} catch (BACnetException e) {
							LOGGER.debug("", e);
						}
					}
					conn.monitor.checkOutReader();
				} catch (InterruptedException ignored) {

				}
			}
			device.monitor.checkOutReader();
		} catch (InterruptedException ignored) {

		}
		return enc;
	}

	public static String[] getCommPorts() {
		String[] portNames;

		switch (SerialNativeInterface.getOsType()) {
		case SerialNativeInterface.OS_LINUX:
			portNames = SerialPortList
					.getPortNames(Pattern.compile("(cu|ttyS|ttyUSB|ttyACM|ttyAMA|rfcomm|ttyO)[0-9]{1,3}"));
			break;
		case SerialNativeInterface.OS_MAC_OS_X:
			portNames = SerialPortList.getPortNames(Pattern.compile("(cu|tty)..*")); // Was
																						// "tty.(serial|usbserial|usbmodem).*")
			break;
		default:
			portNames = SerialPortList.getPortNames();
			break;
		}

		return portNames;
	}

	public static Node actionResultToNode(ActionResult event, Node parent) {
		try {
			String name = event.getParameter("Name", ValueType.STRING).getString();
			// Node node = new Node(name, parent, parent.getLink());
			Node node = parent.createChild(name, true).build();
			setConfigsFromActionResult(node, event);
			return node;
		} catch (Exception e) {
			return null;
		}

	}

	public static void setConfigsFromActionResult(Node node, ActionResult event) {
		String commPortId = null;
		Value customPort = event.getParameter("Comm Port ID (Manual Entry)");
		Value selectedPort = event.getParameter("Comm Port ID");
		if (customPort != null && customPort.getString() != null && customPort.getString().trim().length() > 0) {
			commPortId = customPort.getString();
		} else if (selectedPort != null && selectedPort.getString() != null) {
			commPortId = selectedPort.getString();
		}
		if (commPortId != null) {
			node.setRoConfig("Comm Port ID", new Value(commPortId));
		}
		for (Entry<String, Object> entry : event.getParameters().getMap().entrySet()) {
			if (!"Name".equals(entry.getKey()) && !"Comm Port ID (Manual Entry)".equals(entry.getKey())
					&& !"Comm Port ID".equals(entry.getKey())) {
				node.setRoConfig(entry.getKey(), ValueUtils.toValue(entry.getValue()));
			}
		}
	}

	public static String safeGetRoConfigString(Node node, String config, String def) {
		Value val = node.getRoConfig(config);
		return (val != null && val.getString() != null) ? val.getString() : def;
	}

	public static Number safeGetRoConfigNum(Node node, String config, Number def) {
		Value val = node.getRoConfig(config);
		return (val != null && val.getNumber() != null) ? val.getNumber() : def;
	}

	public static boolean safeGetRoConfigBool(Node node, String config, boolean def) {
		Value val = node.getRoConfig(config);
		return (val != null && val.getBool() != null) ? val.getBool() : def;
	}

	public static String getAndMaybeSetRoConfigString(Node node, String config, String def) {
		String retval = safeGetRoConfigString(node, config, null);
		if (retval == null) {
			node.setRoConfig(config, new Value(def));
			return def;
		} else {
			return retval;
		}
	}

	public static Number getAndMaybeSetRoConfigNum(Node node, String config, Number def) {
		Number retval = safeGetRoConfigNum(node, config, null);
		if (retval == null) {
			node.setRoConfig(config, new Value(def));
			return def;
		} else {
			return retval;
		}
	}

	public static boolean getAndMaybeSetRoConfigBool(Node node, String config, boolean def) {
		boolean retval = safeGetRoConfigBool(node, config, def);
		node.setRoConfig(config, new Value(retval));
		return retval;
	}

	public static Set<String> getDeviceEnum(Map<Integer, RemoteDevice> devices) {
		Set<String> devStringSet = new HashSet<>();
		for (Entry<Integer, RemoteDevice> entry : devices.entrySet()) {
			int id = entry.getKey();
			RemoteDevice d = entry.getValue();
			String devString = (d.getDeviceProperty(PropertyIdentifier.objectName) != null ? d.getName() : "")
					+ " (Instance " + String.valueOf(id) + " at " + d.getAddress() + ")";
			devStringSet.add(devString);
		}
		return devStringSet;
	}

	public static boolean isOneOf(int objectTypeId, ObjectType... types) {
		for (ObjectType type : types) {
			if (type.intValue() == objectTypeId)
				return true;
		}
		return false;
	}

	public static boolean isOneOf(ObjectType objectType, ObjectType... types) {
		return isOneOf(objectType.intValue(), types);
	}

	// public static Encodable booleanToEncodable(Boolean b, ObjectIdentifier
	// oid, PropertyIdentifier pid) {
	// Class<? extends Encodable> clazz =
	// ObjectProperties.getObjectPropertyTypeDefinition(oid.getObjectType(),
	// pid)
	// .getPropertyTypeDefinition().getClazz();
	//
	// if (clazz == BinaryPV.class) {
	// if (b) {
	// return BinaryPV.active;
	// }
	// return BinaryPV.inactive;
	// }
	//
	// if (clazz == UnsignedInteger.class) {
	// return new UnsignedInteger(b ? 1 : 0);
	// }
	//
	// if (clazz == LifeSafetyState.class) {
	// return LifeSafetyState.forId(b ? 1 : 0);
	// }
	//
	// if (clazz == Real.class) {
	// return new Real(b ? 1 : 0);
	// }
	// return BinaryPV.inactive;
	// }
	//
	// public static Encodable multistateToEncodable(int i, ObjectIdentifier
	// oid, PropertyIdentifier pid) {
	// if (i == -1) {
	// return null;
	// }
	// Class<? extends Encodable> clazz =
	// ObjectProperties.getObjectPropertyTypeDefinition(oid.getObjectType(),
	// pid)
	// .getPropertyTypeDefinition().getClazz();
	//
	// if (clazz == BinaryPV.class) {
	// if (i != 0) {
	// return BinaryPV.active;
	// }
	// return BinaryPV.inactive;
	// }
	//
	// if (clazz == UnsignedInteger.class) {
	// return new UnsignedInteger(i);
	// }
	//
	// if (clazz == LifeSafetyState.class) {
	// return LifeSafetyState.forId(i);
	// }
	//
	// if (clazz == Real.class) {
	// return new Real(i);
	// }
	// return new UnsignedInteger(i);
	// }
	//
	// public static Encodable numberToEncodable(Number n, ObjectIdentifier oid,
	// PropertyIdentifier pid) {
	// double d = n.doubleValue();
	// Class<? extends Encodable> clazz =
	// ObjectProperties.getObjectPropertyTypeDefinition(oid.getObjectType(),
	// pid)
	// .getPropertyTypeDefinition().getClazz();
	//
	// if (clazz == BinaryPV.class) {
	// if (d != 0) {
	// return BinaryPV.active;
	// }
	// return BinaryPV.inactive;
	// }
	//
	// if (clazz == UnsignedInteger.class) {
	// return new UnsignedInteger((int) d);
	// }
	//
	// if (clazz == LifeSafetyState.class) {
	// return LifeSafetyState.forId((int) d);
	// }
	//
	// return new Real((float) d);
	// }

	public static DataType getDataType(ObjectType objectType) {
		if (isOneOf(objectType, ObjectType.binaryInput, ObjectType.binaryOutput, ObjectType.binaryValue)) {
			return DataType.BINARY;
		} else if (isOneOf(objectType, ObjectType.multiStateInput, ObjectType.multiStateOutput,
				ObjectType.multiStateValue, ObjectType.command)) {
			return DataType.MULTISTATE;
			// } else if (isOneOf(objectType, ObjectType.analogInput,
			// ObjectType.analogOutput, ObjectType.analogValue,
			// ObjectType.largeAnalogValue, ObjectType.integerValue,
			// ObjectType.positiveIntegerValue)) {
			// return DataType.NUMERIC;
		} else {
			return DataType.OTHER;
		}
	}

	public static List<String> getObjectTypeList() {
		return getEnumeratedStateList(ObjectType.class);
	}

	public static List<String> getPropertyList() {
		return getEnumeratedStateList(PropertyIdentifier.class);
	}

	public static List<String> getEnumeratedStateList(Class<? extends Enumerated> clazz) {
		if (!stateLists.containsKey(clazz)) {
			List<String> lst = new ArrayList<>();
			try {
				int size = (int) clazz.getMethod("size").invoke(null);
				for (int i = 0; i < size; i++) {
					String s = (String) clazz.getMethod("nameForId", int.class).invoke(null, i);
					if (s != null) {
						lst.add(s);
					}
				}
			} catch (ClassCastException | NullPointerException | IllegalAccessException | IllegalArgumentException | InvocationTargetException | NoSuchMethodException | SecurityException e) {
				lst = null;
			}
			stateLists.put(clazz, lst);
		}
		return stateLists.get(clazz);
	}

	public static String getChoiceNameFromLogRecord(LogRecord record) {
		if (record.isLogStatus()) {
			return "LogStatus";
		} else if (record.isBoolean()) {
			return "Boolean";
		} else if (record.isReal()) {
			return "Real";
		} else if (record.isEnumerated()) {
			return "Enumerated";
		} else if (record.isUnsignedInteger()) {
			return "UnsignedInteger";
		} else if (record.isSignedInteger()) {
			return "SignedInteger";
		} else if (record.isBitString()) {
			return "BitString";
		} else if (record.isNull()) {
			return "Null";
		} else if (record.isBACnetError()) {
			return "BACnetError";
		} else if (record.isTimeChange()) {
			return "TimeChange";
		} else if (record.isAny()) {
			return "Any";
		} 
		return "Any";
	}
	
	public static Address toAddress(int netNum, String mac) {
		mac = mac.trim();
		int colon = mac.indexOf(":");
		try {
			if (colon == -1) {
				OctetString os = new OctetString(BACnetUtils.dottedStringToBytes(mac));
				return new Address(netNum, os);
			} else {
				byte[] ip = BACnetUtils.dottedStringToBytes(mac.substring(0, colon));
				int port = Integer.parseInt(mac.substring(colon + 1));
				return IpNetworkUtils.toAddress(netNum, ip, port);
			}
		} catch (Exception e) {
			return null;
		}
	}
	
	public static String getMacString(Address address) {
		try {
			return IpNetworkUtils.toIpPortString(address.getMacAddress());
		} catch (IllegalArgumentException ignore) {
		}
		return BACnetUtils.bytesToDottedString(address.getMacAddress().getBytes());
	}
}
