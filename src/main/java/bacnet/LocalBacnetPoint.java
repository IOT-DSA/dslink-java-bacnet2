package bacnet;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import org.apache.commons.lang3.StringUtils;
import org.dsa.iot.dslink.node.Node;
import org.dsa.iot.dslink.node.Permission;
import org.dsa.iot.dslink.node.Writable;
import org.dsa.iot.dslink.node.actions.Action;

import org.dsa.iot.dslink.node.value.Value;

import org.dsa.iot.dslink.node.value.ValueType;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.serotonin.bacnet4j.exception.BACnetException;
import com.serotonin.bacnet4j.exception.BACnetServiceException;
import com.serotonin.bacnet4j.obj.BACnetObject;
import com.serotonin.bacnet4j.obj.BACnetObjectListener;
import com.serotonin.bacnet4j.obj.ObjectProperties;
import com.serotonin.bacnet4j.obj.PropertyTypeDefinition;

import com.serotonin.bacnet4j.type.Encodable;
import com.serotonin.bacnet4j.type.constructed.BACnetError;
import com.serotonin.bacnet4j.type.constructed.PriorityArray;

import com.serotonin.bacnet4j.type.enumerated.EngineeringUnits;
import com.serotonin.bacnet4j.type.enumerated.EventState;
import com.serotonin.bacnet4j.type.enumerated.LifeSafetyState;
import com.serotonin.bacnet4j.type.enumerated.ObjectType;

import com.serotonin.bacnet4j.type.enumerated.PropertyIdentifier;

import com.serotonin.bacnet4j.type.primitive.Null;
import com.serotonin.bacnet4j.type.primitive.ObjectIdentifier;
import bacnet.properties.LocalEventStateProperty;
import bacnet.properties.LocalOutOfServiceProperty;
import bacnet.properties.LocalPresentValueProperty;
import bacnet.properties.LocalStatusFlagsProperty;
import bacnet.properties.LocalUnitsProperty;

public class LocalBacnetPoint extends EditablePoint {
	private static final Logger LOGGER;

	static {
		LOGGER = LoggerFactory.getLogger(BacnetPoint.class);
	}

	private static PointCounter numPoints = new PointCounter();

	byte typeId = Null.TYPE_ID;

	private int defaultPriority;
	private int objectTypeId;
	private int instanceNumber;
	private String objectTypeDescription;
	private String objectName;
	private String presentValue;
	private boolean cov;
	private boolean settable;

	private DataType dataType;
	private List<String> unitsDescription = new ArrayList<String>();

	public LocalBacnetPoint(LocalDeviceFolder folder, Node parent, ObjectIdentifier objectId) {
		// this.folder = folder;
		// // this.listener = folder.new CovListener(this);
		// this.parent = parent;
		// this.node = null;
		super(folder, parent);

		this.objectId = objectId;
		id = numPoints.increment();
		this.defaultPriority = 8;

		setObjectTypeId(objectId.getObjectType().intValue());
		setObjectTypeDescription(objectId.getObjectType().toString());
		setInstanceNumber(objectId.getInstanceNumber());
		setDataType(Utils.getDataType(objectId.getObjectType()));

		if (Utils.isOneOf(objectId.getObjectType(), ObjectType.binaryInput, ObjectType.binaryOutput,
				ObjectType.binaryValue)) {
			getUnitsDescription().add("0");
			getUnitsDescription().add("1");
		}
	}

	public LocalBacnetPoint(LocalDeviceFolder folder, Node parent, Node node) {
		super(folder, parent, node);

		ObjectType objectType = Utils.parseObjectType(node.getAttribute("object type").getString());
		int instNum = node.getAttribute("object instance number").getNumber().intValue();
		boolean useCov = node.getAttribute("use COV").getBool();
		boolean settable = node.getAttribute("settable").getBool();

		this.objectId = new ObjectIdentifier(objectType, instNum);
		this.propertyId = new PropertyIdentifier(PropertyIdentifier.presentValue.intValue());
		String name = node.getName();

		this.bacnetObj = new BACnetObject(objectType, instNum, name);
		BACnetObjectListener listener = new SimpleBACnetObjectListener();
		bacnetObj.addListener(listener);
		bacnetObj.setLocalDevice(folder.getLocalDevice());

		this.pidToLocalProperty = new HashMap<>();
		
		setCov(useCov);
		setSettable(settable);

		try {
			setObjectTypeId(objectType.intValue());

			setObjectTypeDescription(objectType.toString());
			setInstanceNumber(instNum);
			setDataType(Utils.getDataType(objectType));

			if (DeviceFolder.isOneOf(objectType, ObjectType.binaryInput, ObjectType.binaryOutput,
					ObjectType.binaryValue)) {
				getUnitsDescription().add("0");
				getUnitsDescription().add("1");
			}

			setupNode();

		} catch (Exception e) {
			LOGGER.debug("Object Type Error: ( " + node.getAttribute("object type").getString() + " )", e);
		}
	}

	public int getObjectTypeId() {
		return objectTypeId;
	}

	public void setObjectTypeId(int objectTypeId) {
		this.objectTypeId = objectTypeId;
	}

	public int getInstanceNumber() {
		return instanceNumber;
	}

	public void setInstanceNumber(int instanceNumber) {
		this.instanceNumber = instanceNumber;
	}

	public String getObjectTypeDescription() {
		return objectTypeDescription;
	}

	public void setObjectTypeDescription(String objectTypeDescription) {
		this.objectTypeDescription = objectTypeDescription;

	}

	public String getObjectName() {
		return objectName;
	}

	public void setObjectName(String objectName) {
		if (objectName == null)
			return;
		this.objectName = objectName;
		if (node != null) {

		} else {
			setupNode();
		}
	}

	protected void setupNode() {
		if (node.getValue() == null) {
			node.setValueType(ValueType.STRING);
			node.setValue(new Value(""));
		}

		node.setAttribute("object type", new Value(objectTypeDescription));
		node.setAttribute("object instance number", new Value(instanceNumber));
		node.setAttribute("use COV", new Value(cov));
		node.setAttribute("settable", new Value(settable));
		node.setAttribute("default priority", new Value(defaultPriority));
		node.setAttribute("restore type", new Value("editable point"));

		makePointActions();
		makeSetAction(node, -1);

		setupDataTypeProperty();
		setupObjectNameProperty();
		setupRequiredProperty();
		setupBacnetObject();
	}

	private void setupPresentValueProperty() {
		Node vnode = node.getChild(PROPERTY_PRESENT_VALUE);

		if (null == vnode) {
			vnode = node.createChild(PROPERTY_PRESENT_VALUE).setValueType(ValueType.STRING).setValue(new Value(""))
					.build();

			LocalPresentValueProperty presentValueProperty = new LocalPresentValueProperty(objectId, propertyId, this,
					node, vnode);
			
	        this.pidToLocalProperty.put(propertyId, presentValueProperty);

		}

		vnode.setWritable(Writable.NEVER);

		Value oldval = null;
		if (vnode != null)
			oldval = vnode.getValue();

		if (presentValue != null) {

			ValueType vt;
			Value val;
			switch (dataType) {
			case BINARY: {
				String off = (unitsDescription.size() > 0) ? unitsDescription.get(0) : "0";
				String on = (unitsDescription.size() > 1) ? unitsDescription.get(1) : "1";
				vt = ValueType.makeBool(on, off);
				val = new Value(Boolean.parseBoolean(presentValue) || presentValue.equals("1")
						|| presentValue.equals("Active"));
				break;
			}
			case NUMERIC: {
				vt = ValueType.NUMBER;
				val = new Value(Double.parseDouble(presentValue));
				break;
			}
			case MULTISTATE: {
				Set<String> enums = new HashSet<String>(unitsDescription);
				vt = ValueType.makeEnum(enums);
				int index = Integer.parseInt(presentValue) - 1;
				if (index >= 0 && index < unitsDescription.size())
					val = new Value(unitsDescription.get(index));
				else {
					vt = ValueType.STRING;
					val = new Value(presentValue);
				}
				break;
			}
			case ALPHANUMERIC: {
				vt = ValueType.STRING;
				val = new Value(presentValue);
				break;
			}
			default: {
				vt = ValueType.STRING;
				val = new Value(presentValue);
			}
			}
			if (!areEqual(vt, node.getValueType()) || !val.equals(node.getValue())) {
				node.setValueType(vt);
				node.setValue(val);
			}
			Value units = null;
			if (!(Utils.isOneOf(objectTypeId, ObjectType.binaryInput, ObjectType.binaryOutput, ObjectType.binaryValue,
					ObjectType.multiStateInput, ObjectType.multiStateOutput, ObjectType.multiStateValue,
					ObjectType.lifeSafetyPoint, ObjectType.lifeSafetyZone, ObjectType.trendLog))
					&& unitsDescription.size() > 0) {
				units = new Value(unitsDescription.get(0));
			}
			Node unode = node.getChild(PROPERTY_UNITS);
			if (unode != null) {
				if (units == null)
					node.removeChild(PROPERTY_UNITS);
				else if (!units.equals(unode.getValue()))
					unode.setValue(units);
			} else {
				if (units != null)
					node.createChild(PROPERTY_UNITS).setValueType(ValueType.STRING).setValue(units).build();
			}
			if (vnode != null) {
				if (!areEqual(vt, vnode.getValueType()) || !val.equals(vnode.getValue())) {
					vnode.setValueType(vt);
					vnode.setValue(val);
					LOGGER.debug("presentValue updated to " + val);
				}
			}
		}

		if (settable) {
			if (vnode.getWritable() != Writable.WRITE) {
				makeSetAction(vnode, -1);
				makeSetAction(node, -1);
				PriorityArray pa = null;

				try {
					pa = getPriorityArray();
				} catch (BACnetException e) {
					LOGGER.debug("error", e.getMessage());
					return;
				}
				Value newVal = vnode.getValue();
				if (pa != null && (!newVal.equals(oldval) || vnode.getChildren() == null
						|| vnode.getChildren().size() < pa.getCount() + 3)) {
					makeRelinquishAction(vnode, -1);
					Action act = new Action(Permission.READ, new RelinquishAllHandler());
					vnode.createChild(PROPERTY_RELINQUISH_ALL).setAction(act).build().setSerializable(false);
					refreshPriorities(pa);
				}
			} else {
				refreshPriorities();
			}
		} else {
			if (vnode.getWritable() != Writable.NEVER) {
				vnode.clearChildren();
				vnode.setWritable(Writable.NEVER);
			}
		}

	}

	private void setupDataTypeProperty() {
		if (dataType != null) {
			Node dataTypeNode = node.getChild(PROPERTY_DATA_TYPE);
			if (dataTypeNode != null) {
				if (!new Value(dataType.toString()).equals(dataTypeNode.getValue())) {
					dataTypeNode.setValue(new Value(dataType.toString()));
					LOGGER.debug("dataType updated to " + dataType);
				}
			} else {
				node.createChild(PROPERTY_DATA_TYPE).setValueType(ValueType.STRING)
						.setValue(new Value(dataType.toString())).build();
				LOGGER.debug("dataType set to " + dataType);
			}
		}
	}

	private void setupObjectNameProperty() {
		objectName = node.getName();

		if (objectName != null) {
			Node objectNameNode = node.getChild(PROPERTY_OBJECT_NAME);
			if (objectNameNode != null) {
				if (!new Value(objectName).equals(objectNameNode.getValue())) {
					objectNameNode.setValue(new Value(objectName));
					LOGGER.debug("objectName updated to " + objectName);
				}
			} else {
				node.createChild(PROPERTY_OBJECT_NAME).setValueType(ValueType.STRING).setValue(new Value(objectName))
						.build();
				LOGGER.debug("objectName set to " + objectName);
			}

		}
	}

	protected void setupUnitsProperty(String name) {
		Node propertyNode = node.getChild(name);
		if (null != propertyNode) {

		} else {
			propertyNode = node.createChild(name).setValueType(ValueType.STRING)
					.setValue(new Value(EngineeringUnits.degreeDaysFahrenheit.toString())).build();
		}

		LocalUnitsProperty unitsProperty = new LocalUnitsProperty(objectId, propertyId, this, node, propertyNode);
		this.pidToLocalProperty.put(propertyId, unitsProperty);
	}

	protected void setupEventStateProperty(String name) {
		Node propertyNode = node.getChild(name);
		if (null != propertyNode) {

		} else {
			propertyNode = node.createChild(name).setValueType(ValueType.STRING)
					.setValue(new Value(EventState.normal.toString())).build();
		}

		LocalEventStateProperty eventStateProperty = new LocalEventStateProperty(objectId, propertyId, this, node,
				propertyNode);
		this.pidToLocalProperty.put(propertyId, eventStateProperty);
	}

	protected void setupStatusFlagsProperty(String name) {
		Node propertyNode = node.getChild(name);
		if (null != propertyNode) {

		} else {
			String status = "inAlarm:false, fault:false, overridden:false, outOfService:false";
			propertyNode = node.createChild(name).setValueType(ValueType.STRING).setValue(new Value(status)).build();
		}

		LocalStatusFlagsProperty statusFlagsProperty = new LocalStatusFlagsProperty(objectId, propertyId, this, node,
				propertyNode);

		this.pidToLocalProperty.put(propertyId, statusFlagsProperty);
	}

	protected void setupOutOfServiceProperty(String name) {

		Node propertyNode = node.getChild(name);
		if (null != propertyNode) {

		} else {
			propertyNode = node.createChild(name).setValueType(ValueType.STRING)
					.setValue(new Value(Boolean.FALSE.toString())).build();
		}

		LocalOutOfServiceProperty outOfServiceProperty = new LocalOutOfServiceProperty(objectId, propertyId, this, node,
				propertyNode);

		this.pidToLocalProperty.put(propertyId, outOfServiceProperty);
	}

	private void setupRequiredProperty() {

		ObjectType ot = objectId.getObjectType();

		List<PropertyTypeDefinition> defs = ObjectProperties.getRequiredPropertyTypeDefinitions(ot);

		for (PropertyTypeDefinition def : defs) {
			// System.out.println(def.getClazz() + " : " +
			// def.getPropertyIdentifier());
			initializeRequiredProperty(def);
		}
		LOGGER.info("all properties are set up");
	}

	/*
	 * Main entry for the property setup. Please add new property here.
	 * 
	 * Only initialize the required writable properties. The properties list
	 * below are read-only properties:
	 * 
	 * PropertyIdentifier.objectIdentifier ; PropertyIdentifier.objectName;
	 * PropertyIdentifier.objectType; PropertyIdentifier.propertyList
	 * 
	 */

	private void initializeRequiredProperty(PropertyTypeDefinition def) {

		PropertyIdentifier propId = def.getPropertyIdentifier();

		if (propId.equals(PropertyIdentifier.objectIdentifier)) {
			// read-only property
		} else if (propId.equals(PropertyIdentifier.objectName)) {
			// read-only property
		} else if (propId.equals(PropertyIdentifier.objectType)) {
			// read-only property
		} else if (propId.equals(PropertyIdentifier.propertyList)) {
			// read-only property
		} else if (propId.equals(PropertyIdentifier.presentValue)) {
			setupPresentValueProperty();
		} else if (propId.equals(PropertyIdentifier.units)) {
			setupUnitsProperty(PropertyIdentifier.units.toString());
		} else if (propId.equals(PropertyIdentifier.statusFlags)) {
			setupStatusFlagsProperty(PropertyIdentifier.statusFlags.toString());
		} else if (propId.equals(PropertyIdentifier.eventState)) {
			setupEventStateProperty(PropertyIdentifier.eventState.toString());
		} else if (propId.equals(PropertyIdentifier.outOfService)) {
			setupOutOfServiceProperty(PropertyIdentifier.outOfService.toString());
		}

	}

	private void setupBacnetObject() {
		try {
			folder.getLocalDevice().addObject(bacnetObj);
		} catch (BACnetServiceException e) {
			LOGGER.debug("error", e);
		}
	}

	protected void handleSet(Value newVal, int priority, boolean raw) {
		if (folder.getConnection().getLocalDevice() == null) {
			folder.getConnection().stop();
			return;
		}
		if (folder.getRoot().getLocalDevice() == null)
			return;

		if (dataType == DataType.BINARY) {
			if (raw) {

			} else {
				String on = (unitsDescription.size() > 1) ? unitsDescription.get(1) : "1";
				newVal = new Value(String.valueOf(newVal.getString().equals(on)));
			}
		} else if (dataType == DataType.MULTISTATE) {
			if (!raw) {
				int i = unitsDescription.indexOf(newVal.getString());
				if (i == -1)
					return;
				newVal = new Value(String.valueOf(i));
			}
		}

		try {
			Encodable enc = Utils.valueToEncodable(dataType, newVal, objectId.getObjectType(),
					PropertyIdentifier.presentValue, null);
			bacnetObj.writeProperty(PropertyIdentifier.presentValue, enc);

		} catch (Exception e) {
			LOGGER.debug("error: ", e);
		}

	}

	public DataType getDataType() {
		return dataType;
	}

	public void setDataType(DataType dataType) {
		if (dataType == DataType.NUMERIC && presentValue != null) {
			try {
				Double.parseDouble(presentValue);
			} catch (NumberFormatException e) {
				dataType = DataType.ALPHANUMERIC;
			}
		}
		this.dataType = dataType;
	}

	public List<String> getUnitsDescription() {
		return unitsDescription;
	}

	public void setUnitsDescription(List<String> unitsDescription) {
		this.unitsDescription = unitsDescription;
	}

	// public String getEffectivePeriod() {
	// return effectivePeriod;
	// }
	//
	// public void setEffectivePeriod(String ep) {
	// this.effectivePeriod = ep;
	// }

	// public JsonArray getWeeklySchedule() {
	// return weeklySchedule;
	// }
	//
	// public void setWeeklySchedule(JsonArray ws) {
	// this.weeklySchedule = ws;
	// }
	//
	// public JsonArray getExceptionSchedule() {
	// return exceptionSchedule;
	// }
	//
	// public void setExceptionSchedule(JsonArray es) {
	// this.exceptionSchedule = es;
	// }

	// public JsonArray getDateList() {
	// return dateList;
	// }
	//
	// public void setDateList(JsonArray dl) {
	// this.dateList = dl;
	// }

	public String getPresentValue() {
		return presentValue;
	}

	public void setPresentValue(String presentValue, PropertyIdentifier propertyId) {
		this.propertyId = propertyId;
		node.setAttribute("pid", new Value(propertyId.intValue()));
		this.presentValue = presentValue;
		setDataType(dataType);
	}

	public boolean isCov() {
		return cov;
	}

	public void setCov(boolean cov) {
		this.cov = cov;
	}

	public boolean isSettable() {
		return settable;
	}

	public void setSettable(boolean settable) {
		this.settable = settable;

	}

	// public int getDefaultPriority() {
	// return defaultPriority;
	// }
	//
	// public void setDefaultPriority(int p) {
	// this.defaultPriority = p;
	// }

	// public String getEngineeringUnits() {
	// return engineeringUnits;
	// }
	//
	// public void setEngineeringUnits(String engineeringUnits) {
	// this.engineeringUnits = engineeringUnits;
	// }

	// public String getReferenceDevice() {
	// return referenceDevice;
	// }
	//
	// public void setReferenceDevice(String referenceDevice) {
	// this.referenceDevice = referenceDevice;
	// }
	//
	// public String getReferenceObject() {
	// return referenceObject;
	// }
	//
	// public void setReferenceObject(String referenceObject) {
	// this.referenceObject = referenceObject;
	// }
	//
	// public String getReferenceProperty() {
	// return referenceProperty;
	// }
	//
	// public void setReferenceProperty(String referenceProperty) {
	// this.referenceProperty = referenceProperty;
	// }

	private static boolean areEqual(ValueType a, ValueType b) {
		return a == b || (a != null && b != null && a.toJsonString().equals(b.toJsonString()));
	}

	private void refreshPriorities() {
		refreshPriorities(null);
	}

	private void refreshPriorities(PriorityArray priorities) {
		Node vnode = node.getChild(PROPERTY_PRESENT_VALUE);
		if (priorities == null) {
			try {
				priorities = getPriorityArray();
			} catch (BACnetException e) {
				LOGGER.error("error: ", e);
				return;
			}
		}
		if (priorities == null)
			return;
		for (int i = 1; i <= priorities.getCount(); i++) {
			Encodable enc = priorities.get(i).getValue();
			String p = enc.toString();
			ValueType vt;
			Value val = null;
			boolean isnull = (enc instanceof Null);
			switch (dataType) {
			case BINARY: {
				String off = (unitsDescription.size() > 0) ? unitsDescription.get(0) : "0";
				String on = (unitsDescription.size() > 1) ? unitsDescription.get(1) : "1";
				vt = ValueType.makeBool(on, off);
				if (!isnull)
					val = new Value(Boolean.parseBoolean(p) || p.equals("1") || p.equals("Active"));
				break;
			}
			case NUMERIC: {
				vt = ValueType.NUMBER;
				if (!isnull)
					val = new Value(Double.parseDouble(p));
				break;
			}
			case MULTISTATE: {
				Set<String> enums = new HashSet<String>(unitsDescription);
				vt = ValueType.makeEnum(enums);
				int index = Integer.parseInt(p) - 1;
				if (!isnull) {
					if (index >= 0 && index < unitsDescription.size())
						val = new Value(unitsDescription.get(index));
					else
						val = new Value(p);
				}
				break;
			}
			case ALPHANUMERIC: {
				vt = ValueType.STRING;
				if (!isnull)
					val = new Value(p);
				break;
			}
			default: {
				vt = ValueType.STRING;
				if (!isnull)
					val = new Value(p);
			}
			}
			Node pnode = vnode.getChild("Priority " + i);
			if (pnode != null) {
				pnode.setValueType(vt);
				pnode.setValue(val);
				if (pnode.getChild(PROPERTY_RELINQUISH) == null) {
					makeSetAction(pnode, i);
					makeRelinquishAction(pnode, i);
				}
			} else {
				pnode = vnode.createChild("Priority " + i).setValueType(vt).setValue(val).build();
				makeSetAction(pnode, i);
				makeRelinquishAction(pnode, i);
			}

		}
	}

	private void makeRelinquishAction(Node valnode, int priority) {
		Action act = new Action(Permission.READ, new RelinquishHandler(priority));
		valnode.createChild("relinquish").setAction(act).build().setSerializable(false);
	}

	private PriorityArray getPriorityArray() throws BACnetException {
		if (folder.getConnection().getLocalDevice() == null) {
			folder.conn.stop();
			return null;
		}
		if (folder.getRoot().getLocalDevice() == null)
			return null;
		Encodable e = null;

		if (e instanceof BACnetError)
			return null;
		return (PriorityArray) e;
	}

	public static String getPrettyPresentValue(int objectTypeId, String presentValue, List<String> unitsDescription,
			String referenceObjectTypeDescription, int referenceInstanceNumber, int referenceDeviceId) {
		if (DeviceFolder.isOneOf(objectTypeId, ObjectType.binaryInput, ObjectType.binaryOutput,
				ObjectType.binaryValue)) {
			if ("0".equals(presentValue)) {
				if (unitsDescription.size() > 0)
					return unitsDescription.get(0);
				else
					return "0";
			}
			if ("1".equals(presentValue)) {
				if (unitsDescription.size() > 1)
					return unitsDescription.get(1);
				else
					return "1";
			}
		} else if (DeviceFolder.isOneOf(objectTypeId, ObjectType.multiStateInput, ObjectType.multiStateOutput,
				ObjectType.multiStateValue)) {
			try {
				int index = Integer.parseInt(presentValue) - 1;
				if (index >= 0) {
					if (index < unitsDescription.size())
						return unitsDescription.get(index);
					else
						return presentValue;
				}

			} catch (NumberFormatException e) {
				// no op
			}
		} else if (DeviceFolder.isOneOf(objectTypeId, ObjectType.lifeSafetyPoint, ObjectType.lifeSafetyZone)) {
			try {
				int index = Integer.parseInt(presentValue);
				return new LifeSafetyState(index).toString();
			} catch (NumberFormatException e) {
				// no op
			}
		} else if (DeviceFolder.isOneOf(objectTypeId, ObjectType.trendLog)) {
			if (StringUtils.isEmpty(referenceObjectTypeDescription))
				return "";
			return referenceObjectTypeDescription + " " + referenceInstanceNumber + " @ " + ObjectType.device.toString()
					+ " " + referenceDeviceId + ", " + presentValue;
		} else if (unitsDescription.size() > 0)
			return presentValue + " " + unitsDescription.get(0);

		return presentValue;
	}

	@Override
	public int hashCode() {
		final int prime = 31;
		int result = 1;
		result = prime * result + instanceNumber;
		result = prime * result + objectTypeId;
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
		LocalBacnetPoint other = (LocalBacnetPoint) obj;
		if (instanceNumber != other.instanceNumber)
			return false;
		if (objectTypeId != other.objectTypeId)
			return false;
		return true;
	}

	private static class PointCounter {
		private int count = 0;

		int increment() {
			int r = count;
			count += 1;
			return r;
		}
	}

	private LocalBacnetPoint getMe() {
		return this;
	}

	public class SimpleBACnetObjectListener implements BACnetObjectListener {

		@Override
		public void propertyChange(PropertyIdentifier arg0, Encodable arg1, Encodable arg2) {
			// TODO Auto-generated method stub

			// TBD: update the value of point node
			node.setValue(new Value("changed"));

		}
	}

	@Override
	protected void makeCopy() {
		// TODO Auto-generated method stub

	}
}
