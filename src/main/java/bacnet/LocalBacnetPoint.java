package bacnet;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import org.dsa.iot.dslink.node.Node;
import org.dsa.iot.dslink.node.NodeBuilder;
import org.dsa.iot.dslink.node.value.Value;
import org.dsa.iot.dslink.node.value.ValueType;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.serotonin.bacnet4j.exception.BACnetServiceException;
import com.serotonin.bacnet4j.obj.BACnetObject;
import com.serotonin.bacnet4j.obj.BACnetObjectListener;
import com.serotonin.bacnet4j.obj.ObjectProperties;
import com.serotonin.bacnet4j.obj.PropertyTypeDefinition;
import com.serotonin.bacnet4j.type.Encodable;
import com.serotonin.bacnet4j.type.enumerated.ObjectType;
import com.serotonin.bacnet4j.type.enumerated.PropertyIdentifier;
import com.serotonin.bacnet4j.type.primitive.ObjectIdentifier;

import bacnet.properties.LocalBacnetProperty;
import bacnet.properties.LocalBinaryPVProperty;
import bacnet.properties.LocalBinaryStateTextProperty;
import bacnet.properties.LocalBooleanProperty;
import bacnet.properties.LocalCharacterStringProperty;
import bacnet.properties.LocalEventStateProperty;
import bacnet.properties.LocalNumberOfStatesProperty;
import bacnet.properties.LocalPolarityProperty;
import bacnet.properties.LocalRealProperty;
import bacnet.properties.LocalStateTextProperty;
import bacnet.properties.LocalStatusFlagsProperty;
import bacnet.properties.LocalUnitsProperty;
import bacnet.properties.LocalUnsignedIntegerProperty;

public class LocalBacnetPoint extends EditablePoint {
	private static final Logger LOGGER;

	static {
		LOGGER = LoggerFactory.getLogger(BacnetPoint.class);
	}

	private static PointCounter numPoints = new PointCounter();

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
		// this.propertyId = new
		// PropertyIdentifier(PropertyIdentifier.presentValue.intValue());
		String name = node.getName();

		this.bacnetObj = new BACnetObject(objectType, instNum, name);
		BACnetObjectListener listener = new SimpleBACnetObjectListener();
		bacnetObj.addListener(listener);
		bacnetObj.setLocalDevice(folder.getLocalDevice());

		this.propertyIdToLocalProperty = new HashMap<>();

		setCov(useCov);
		setSettable(settable);

		try {
			setObjectTypeId(objectType.intValue());

			setObjectTypeDescription(objectType.toString());
			setInstanceNumber(instNum);
			setDataType(Utils.getDataType(objectType));

			if (Utils.isOneOf(objectType, ObjectType.binaryInput, ObjectType.binaryOutput, ObjectType.binaryValue)) {
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

	private void setupPresentValueProperty(PropertyIdentifier pid) {
		Node presentValueNode = buildPropertyNode(pid);

		LocalBacnetProperty presentValueProperty = null;
		switch (dataType) {
		case BINARY: {
			presentValueProperty = new LocalBinaryPVProperty(objectId, pid, this, node, presentValueNode, true);
			break;
		}
		case NUMERIC: {
			presentValueProperty = new LocalRealProperty(objectId, pid, this, node, presentValueNode);
			break;
		}
		case MULTISTATE: {
			presentValueProperty = new LocalUnsignedIntegerProperty(objectId, pid, this, node, presentValueNode, true);
			break;
		}
		case ALPHANUMERIC: {
			presentValueProperty = new LocalCharacterStringProperty(objectId, pid, this, node, presentValueNode);
			break;
		}
		case BOOLEAN: {
			presentValueProperty = new LocalBooleanProperty(objectId, pid, this, node, presentValueNode);
			break;
		}
		}

		if (presentValueProperty != null) {
			propertyIdToLocalProperty.put(pid, presentValueProperty);
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

	protected void setupUnitsProperty(PropertyIdentifier pid) {
		Node propertyNode = this.buildPropertyNode(pid);
		LocalUnitsProperty unitsProperty = new LocalUnitsProperty(objectId, PropertyIdentifier.units, this, node,
				propertyNode);

		propertyIdToLocalProperty.put(PropertyIdentifier.units, unitsProperty);
	}

	protected void setupBinaryStateTextProperty(PropertyIdentifier pid, boolean state) {
		Node propertyNode = buildPropertyNode(pid);
		String defText = (state) ? "true" : "false";
		LocalBinaryStateTextProperty textProperty = new LocalBinaryStateTextProperty(objectId, pid, this, node,
				propertyNode, defText);

		propertyIdToLocalProperty.put(pid, textProperty);

	}

	protected void setupStateTextProperty(PropertyIdentifier pid) {
		Node propertyNode = buildPropertyNode(pid);
		LocalStateTextProperty stateTextProperty = new LocalStateTextProperty(objectId, PropertyIdentifier.stateText,
				this, node, propertyNode);

		propertyIdToLocalProperty.put(PropertyIdentifier.stateText, stateTextProperty);
	}

	protected void setupEventStateProperty(PropertyIdentifier pid) {
		Node propertyNode = buildPropertyNode(pid);
		LocalEventStateProperty eventStateProperty = new LocalEventStateProperty(objectId,
				PropertyIdentifier.eventState, this, node, propertyNode);

		propertyIdToLocalProperty.put(PropertyIdentifier.eventState, eventStateProperty);
	}

	protected void setupPolarityProperty(PropertyIdentifier pid) {
		Node propertyNode = buildPropertyNode(pid);
		LocalPolarityProperty polarityProperty = new LocalPolarityProperty(objectId, PropertyIdentifier.polarity, this,
				node, propertyNode);

		propertyIdToLocalProperty.put(PropertyIdentifier.polarity, polarityProperty);
	}

	protected void setupRelinquishDefaultProperty(PropertyIdentifier pid) {
		setupPresentValueProperty(pid);
	}

	/*
	 * For any property whose type is UnsignedInteger
	 */

	protected void setupUnsignedIntegerProperty(PropertyIdentifier pid) {
		Node propertyNode = buildPropertyNode(pid);
		LocalUnsignedIntegerProperty nosProperty = new LocalUnsignedIntegerProperty(objectId, pid, this, node,
				propertyNode, false);

		propertyIdToLocalProperty.put(PropertyIdentifier.numberOfStates, nosProperty);
	}

	/*
	 * For any property whose type is boolean
	 */
	protected void setupBooleanProperty(PropertyIdentifier pid) {
		Node propertyNode = buildPropertyNode(pid);
		LocalBooleanProperty booleanProperty = new LocalBooleanProperty(objectId, pid, this, node, propertyNode);

		propertyIdToLocalProperty.put(pid, booleanProperty);
	}

	/*
	 * For any property whose type is characterString
	 */
	protected void setupCharacterStringProperty(PropertyIdentifier pid) {
		Node propertyNode = buildPropertyNode(pid);
		LocalCharacterStringProperty descriptionProperty = new LocalCharacterStringProperty(objectId, pid, this, node,
				propertyNode);

		this.propertyIdToLocalProperty.put(pid, descriptionProperty);
	}

	protected void setupNumberOfStatesProperty(PropertyIdentifier pid) {
		Node propertyNode = buildPropertyNode(pid);
		LocalNumberOfStatesProperty numberOfStateProperty = new LocalNumberOfStatesProperty(objectId,
				PropertyIdentifier.numberOfStates, this, node, propertyNode, 3);

		propertyIdToLocalProperty.put(PropertyIdentifier.numberOfStates, numberOfStateProperty);
	}

	protected void setupStatusFlagsProperty(PropertyIdentifier pid) {
		Node propertyNode = buildPropertyNode(pid);
		LocalStatusFlagsProperty statusFlagsProperty = new LocalStatusFlagsProperty(objectId,
				PropertyIdentifier.statusFlags, this, node, propertyNode);

		propertyIdToLocalProperty.put(PropertyIdentifier.statusFlags, statusFlagsProperty);
	}

	private Node buildPropertyNode(PropertyIdentifier pid) {
		String name = pid.toString();
		Node propertyNode = node.getChild(name);
		NodeBuilder b = null;

		if (null == propertyNode) {
			b = node.createChild(name);
			propertyNode = b.getChild();
		}
		if (b != null) {
			b.build();
		}
		return propertyNode;
	}

	private void setupRequiredProperty() {

		ObjectType ot = objectId.getObjectType();

		List<PropertyTypeDefinition> defs = ObjectProperties.getPropertyTypeDefinitions(ot);
		// LOGGER.info("Required properties: " + defs.size());
		for (PropertyTypeDefinition def : defs) {
			// LOGGER.info(def.getClazz() + " : " +
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
		} else if (propId.equals(PropertyIdentifier.priorityArray)) {
			// read-only property
		} else if (propId.equals(PropertyIdentifier.presentValue)) {
			setupPresentValueProperty(PropertyIdentifier.presentValue);
		} else if (propId.equals(PropertyIdentifier.units)) {
			setupUnitsProperty(PropertyIdentifier.units);
		} else if (propId.equals(PropertyIdentifier.statusFlags)) {
			setupStatusFlagsProperty(PropertyIdentifier.statusFlags);
		} else if (propId.equals(PropertyIdentifier.eventState)) {
			setupEventStateProperty(PropertyIdentifier.eventState);
		} else if (propId.equals(PropertyIdentifier.outOfService)) {
			setupBooleanProperty(PropertyIdentifier.outOfService);
		} else if (propId.equals(PropertyIdentifier.polarity)) {
			setupPolarityProperty(PropertyIdentifier.polarity);
		} else if (propId.equals(PropertyIdentifier.relinquishDefault)) {
			setupRelinquishDefaultProperty(PropertyIdentifier.relinquishDefault);
		} else if (propId.equals(PropertyIdentifier.numberOfStates)) {
			this.setupUnitsProperty(PropertyIdentifier.numberOfStates);
		} else if (propId.equals(PropertyIdentifier.inactiveText)) {
			setupBinaryStateTextProperty(PropertyIdentifier.inactiveText, false);
		} else if (propId.equals(PropertyIdentifier.activeText)) {
			setupBinaryStateTextProperty(PropertyIdentifier.activeText, true);
		} else if (propId.equals(PropertyIdentifier.stateText)) {
			setupStateTextProperty(PropertyIdentifier.stateText);
		} else if (propId.equals(PropertyIdentifier.description)) {
			setupCharacterStringProperty(PropertyIdentifier.description);
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

	public String getPresentValue() {
		return presentValue;
	}

	public void setPresentValue(String presentValue, PropertyIdentifier propertyId) {
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

	private static boolean areEqual(ValueType a, ValueType b) {
		return a == b || (a != null && b != null && a.toJsonString().equals(b.toJsonString()));
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

	public void updatePointValue(Encodable enc) {
		String presentValue = enc.toString();
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
		}
	}

	public void restoreLastSession() {
	}

}
