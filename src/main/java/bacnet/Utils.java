package bacnet;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Set;
import java.util.regex.Pattern;

import org.dsa.iot.dslink.node.Node;
import org.dsa.iot.dslink.node.actions.ActionResult;
import org.dsa.iot.dslink.node.value.Value;
import org.dsa.iot.dslink.node.value.ValueType;
import org.dsa.iot.dslink.node.value.ValueUtils;
import com.serotonin.bacnet4j.RemoteDevice;
import com.serotonin.bacnet4j.obj.ObjectProperties;
import com.serotonin.bacnet4j.type.Encodable;
import com.serotonin.bacnet4j.type.enumerated.BinaryPV;
import com.serotonin.bacnet4j.type.enumerated.LifeSafetyState;
import com.serotonin.bacnet4j.type.enumerated.ObjectType;
import com.serotonin.bacnet4j.type.enumerated.PropertyIdentifier;
import com.serotonin.bacnet4j.type.primitive.Enumerated;
import com.serotonin.bacnet4j.type.primitive.ObjectIdentifier;
import com.serotonin.bacnet4j.type.primitive.Real;
import com.serotonin.bacnet4j.type.primitive.UnsignedInteger;

import jssc.SerialNativeInterface;
import jssc.SerialPortList;

public class Utils {

//	private static final Logger LOGGER = LoggerFactory.getLogger(Utils.class);

	private static final Map<Class<? extends Enumerated>, List<String>> stateLists = new HashMap<Class<? extends Enumerated>, List<String>>();

	static {
		getObjectTypeList();
		getPropertyList();
	}
	
//	private static final List<String> objectTypeList;
//	static {
//		objectTypeList = new ArrayList<String>();
//		for (int i = 0; i < ObjectType.size(); i++) {
//			String s = ObjectType.nameForId(i);
//			if (s != null) {
//				objectTypeList.add(s);
//			}
//		}
//	}
//	private static final List<String> propertyList;
//	static {
//		propertyList = new ArrayList<String>();
//		for (int i = 0; i < PropertyIdentifier.size(); i++) {
//			String s = PropertyIdentifier.nameForId(i);
//			if (s != null) {
//				propertyList.add(s);
//			}
//		}
//	}

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
		return (val != null && val.getBool() != null) ? val.getBool().booleanValue() : def;
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
		Set<String> devStringSet = new HashSet<String>();
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

//	public static Encodable booleanToEncodable(Boolean b, ObjectIdentifier oid, PropertyIdentifier pid) {
//		Class<? extends Encodable> clazz = ObjectProperties.getObjectPropertyTypeDefinition(oid.getObjectType(), pid)
//				.getPropertyTypeDefinition().getClazz();
//
//		if (clazz == BinaryPV.class) {
//			if (b) {
//				return BinaryPV.active;
//			}
//			return BinaryPV.inactive;
//		}
//
//		if (clazz == UnsignedInteger.class) {
//			return new UnsignedInteger(b ? 1 : 0);
//		}
//
//		if (clazz == LifeSafetyState.class) {
//			return LifeSafetyState.forId(b ? 1 : 0);
//		}
//
//		if (clazz == Real.class) {
//			return new Real(b ? 1 : 0);
//		}
//		return BinaryPV.inactive;
//	}
//
//	public static Encodable multistateToEncodable(int i, ObjectIdentifier oid, PropertyIdentifier pid) {
//		if (i == -1) {
//			return null;
//		}
//		Class<? extends Encodable> clazz = ObjectProperties.getObjectPropertyTypeDefinition(oid.getObjectType(), pid)
//				.getPropertyTypeDefinition().getClazz();
//
//		if (clazz == BinaryPV.class) {
//			if (i != 0) {
//				return BinaryPV.active;
//			}
//			return BinaryPV.inactive;
//		}
//
//		if (clazz == UnsignedInteger.class) {
//			return new UnsignedInteger(i);
//		}
//
//		if (clazz == LifeSafetyState.class) {
//			return LifeSafetyState.forId(i);
//		}
//
//		if (clazz == Real.class) {
//			return new Real(i);
//		}
//		return new UnsignedInteger(i);
//	}
//
//	public static Encodable numberToEncodable(Number n, ObjectIdentifier oid, PropertyIdentifier pid) {
//		double d = n.doubleValue();
//		Class<? extends Encodable> clazz = ObjectProperties.getObjectPropertyTypeDefinition(oid.getObjectType(), pid)
//				.getPropertyTypeDefinition().getClazz();
//
//		if (clazz == BinaryPV.class) {
//			if (d != 0) {
//				return BinaryPV.active;
//			}
//			return BinaryPV.inactive;
//		}
//
//		if (clazz == UnsignedInteger.class) {
//			return new UnsignedInteger((int) d);
//		}
//
//		if (clazz == LifeSafetyState.class) {
//			return LifeSafetyState.forId((int) d);
//		}
//
//		return new Real((float) d);
//	}

	public static DataType getDataType(ObjectType objectType) {
		if (isOneOf(objectType, ObjectType.binaryInput, ObjectType.binaryOutput, ObjectType.binaryValue)) {
			return DataType.BINARY;
		} else if (isOneOf(objectType, ObjectType.multiStateInput, ObjectType.multiStateOutput,
				ObjectType.multiStateValue, ObjectType.command)) {
			return DataType.MULTISTATE;
//		} else if (isOneOf(objectType, ObjectType.analogInput, ObjectType.analogOutput, ObjectType.analogValue,
//				ObjectType.largeAnalogValue, ObjectType.integerValue, ObjectType.positiveIntegerValue)) {
//			return DataType.NUMERIC;
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
			List<String> lst = new ArrayList<String>();
			try {
				int size = (int) clazz.getMethod("size").invoke(null);
				for (int i=0; i<size; i++) {
					String s = (String) clazz.getMethod("nameForId", int.class).invoke(null, i);
					if (s != null) {
						lst.add(s);
					}
				}
			} catch (Exception e) {
				lst = null;
			}
			stateLists.put(clazz, lst);
		}
		return stateLists.get(clazz);
	}

}
