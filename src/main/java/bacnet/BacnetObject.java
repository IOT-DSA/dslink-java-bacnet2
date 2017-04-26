package bacnet;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import org.apache.commons.lang3.tuple.Pair;
import org.dsa.iot.dslink.node.Node;
import org.dsa.iot.dslink.node.Permission;
import org.dsa.iot.dslink.node.Writable;
import org.dsa.iot.dslink.node.actions.Action;
import org.dsa.iot.dslink.node.actions.ActionResult;
import org.dsa.iot.dslink.node.actions.Parameter;
import org.dsa.iot.dslink.node.value.Value;
import org.dsa.iot.dslink.node.value.ValuePair;
import org.dsa.iot.dslink.node.value.ValueType;
import org.dsa.iot.dslink.util.handler.Handler;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.serotonin.bacnet4j.ServiceFuture;
import com.serotonin.bacnet4j.exception.BACnetException;
import com.serotonin.bacnet4j.service.confirmed.WritePropertyRequest;
import com.serotonin.bacnet4j.type.Encodable;
import com.serotonin.bacnet4j.type.constructed.PropertyValue;
import com.serotonin.bacnet4j.type.constructed.SequenceOf;
import com.serotonin.bacnet4j.type.enumerated.ObjectType;
import com.serotonin.bacnet4j.type.enumerated.PropertyIdentifier;
import com.serotonin.bacnet4j.type.error.BACnetError;
import com.serotonin.bacnet4j.type.error.BaseError;
import com.serotonin.bacnet4j.type.primitive.CharacterString;
import com.serotonin.bacnet4j.type.primitive.Enumerated;
import com.serotonin.bacnet4j.type.primitive.Null;
import com.serotonin.bacnet4j.type.primitive.ObjectIdentifier;
import com.serotonin.bacnet4j.type.primitive.Real;
import com.serotonin.bacnet4j.type.primitive.SignedInteger;
import com.serotonin.bacnet4j.type.primitive.UnsignedInteger;
import com.serotonin.bacnet4j.util.RequestUtils;

public class BacnetObject extends BacnetProperty {
	private static final Logger LOGGER = LoggerFactory.getLogger(BacnetObject.class);
	
	static final String ACTION_DISCOVER_PROPERTIES = "discover properties";
	static final String ACTION_ADD_PROPERTY = "add property";
	static final String ACTION_EDIT = "edit";
	static final String ACTION_WRITE = "write";
	static final String ACTION_RELINQUISH = "relinquish";
	
	private DataType dataType = null;
	boolean useCov;
	boolean headlessPolling;
	int writePriority;
	
	Set<BacnetProperty> properties = new HashSet<BacnetProperty>();
	private int covSubCount = 0;
	Object lock = new Object();
	
	private List<String> stateText = new ArrayList<String>(2);
	
	private BacnetProperty hiddenNameProp;
	private BacnetProperty hiddenStateTextProp;
	private BacnetProperty hiddenActiveTextProp;
	private BacnetProperty hiddenInactiveTextProp;
	
	BacnetObject(BacnetDevice device, Node node, ObjectIdentifier oid) {
		super(device, node, oid, PropertyIdentifier.presentValue);
		device.objects.add(this);
		this.object = this;
		useCov = Utils.getAndMaybeSetRoConfigBool(node, "Use COV", false);
		headlessPolling = Utils.getAndMaybeSetRoConfigBool(node, "Enable Headless Polling", false);
		writePriority = Utils.getAndMaybeSetRoConfigNum(node, "Write Priority", 16).intValue();
		this.hiddenNameProp = new HiddenProperty(device, this, oid, PropertyIdentifier.objectName);
		this.hiddenStateTextProp = new HiddenProperty(device, this, oid, PropertyIdentifier.stateText);
		this.hiddenActiveTextProp = new HiddenProperty(device, this, oid, PropertyIdentifier.activeText);
		this.hiddenInactiveTextProp = new HiddenProperty(device, this, oid, PropertyIdentifier.inactiveText);
		stateText.add("inactive");
		stateText.add("active");
	}
	
	void restoreLastSession() {
		if (node.getChildren() != null) {
			for (Node child : node.getChildren().values()) {
				try {
					PropertyIdentifier propid = PropertyIdentifier.forName(child.getName());
					addProperty(propid, child);
				} catch (Exception e) {
					child.delete(false);
				}
			}
		}
		
		init();
	}
	
	void init() {
		setup();
//		addProperties();
		makeSettable();
		makeRelinquishAction();
		makeDiscoverAction();
		makeAddPropertyAction();
		makeEditAction();
	}
	
	private void addProperties() {
		SequenceOf<PropertyIdentifier> proplist = getPropertyList();
		if (proplist != null) {
			for (PropertyIdentifier propid: proplist) {
				addProperty(propid);
			}
		}
	}
	
	private void addProperty(PropertyIdentifier propid) {
		addProperty(propid, null);
	}
	
	private void addProperty(PropertyIdentifier propid, Node child) {
		String name = propid.toString();
		if (name.matches("^\\d+$") || (child == null && node.hasChild(name, true))) {
			return;
		}
		
		if (child == null) {
			child = node.createChild(name, true).setValueType(ValueType.STRING).setValue(new Value("")).build();
		}
		BacnetProperty bp = new BacnetProperty(device, this, child, oid, propid);
		bp.setup();
	}
	
	private SequenceOf<PropertyIdentifier> getPropertyList() {
		try {
			device.monitor.checkInReader();
			if (device.remoteDevice != null) {
				try {
					device.conn.monitor.checkInReader();
					if (device.conn.localDevice != null) {
						try {
							return (SequenceOf<PropertyIdentifier>) RequestUtils.sendReadPropertyAllowNull(device.conn.localDevice, device.remoteDevice, oid, PropertyIdentifier.propertyList);
						} catch (BACnetException e) {
							LOGGER.debug("", e);
						}
					}
					device.conn.monitor.checkOutReader();
				} catch (InterruptedException e) {
					
				}
			}
			device.monitor.checkOutReader();
		} catch (InterruptedException e) {
			
		}
		return null;
	}
	
	private void makeRelinquishAction() {
		Action act = new Action(Permission.READ, new Handler<ActionResult>() {
			@Override
			public void handle(ActionResult event) {
				handleRelinquish(event);
			}
		});
		act.addParameter(new Parameter("Priority", ValueType.NUMBER, new Value(writePriority)));
		Node anode = node.getChild(ACTION_RELINQUISH, true);
		if (anode == null) {
			node.createChild(ACTION_RELINQUISH, true).setAction(act).build().setSerializable(false);
		} else {
			anode.setAction(act);
		}
	}
	
	private void handleRelinquish(ActionResult event) {
		int priority = event.getParameter("Priority", ValueType.NUMBER).getNumber().intValue();
		
		write(null, priority);
	}
	
	private void makeSettable() {
		if (Utils.isOneOf(oid.getObjectType(), ObjectType.analogOutput, ObjectType.analogValue,
				ObjectType.binaryOutput, ObjectType.binaryValue, ObjectType.multiStateOutput,
				ObjectType.multiStateValue)) {
			node.setWritable(Writable.WRITE);
			node.getListener().setValueHandler(new Handler<ValuePair>() {
				@Override
				public void handle(ValuePair event) {
					handleSet(event);
				}
			});
		}
	}
	
	private void handleSet(ValuePair event) {
		if (!event.isFromExternalSource()) {
			return;
		}
		Value newval = event.getCurrent();
		write(newval, writePriority);
	}
	
	private void write(Value newval, int priority) {
		Encodable enc = null;
		if (newval == null) {
			enc = new Null();
		} else if (Utils.isOneOf(oid.getObjectType(), ObjectType.binaryOutput, ObjectType.binaryValue)) {
			enc = Utils.booleanToEncodable(newval.getBool(), oid, pid);
		} else if (Utils.isOneOf(oid.getObjectType(), ObjectType.multiStateOutput, ObjectType.multiStateValue)) {
			int i = stateText.indexOf(newval.getString());
			enc = Utils.multistateToEncodable(i, oid, pid);
		} else if (Utils.isOneOf(oid.getObjectType(), ObjectType.analogOutput, ObjectType.analogValue)) {
			enc = Utils.numberToEncodable(newval.getNumber(), oid, pid);
		}
		if (enc == null) {
			return;
		}
		ServiceFuture sf = null;
		try {
			device.monitor.checkInReader();
			if (device.remoteDevice != null) {
				try {
					device.conn.monitor.checkInReader();
					if (device.conn.localDevice != null) {
						sf = device.conn.localDevice.send(device.remoteDevice, new WritePropertyRequest(oid, pid, null, enc, new UnsignedInteger(priority)));
					}
					device.conn.monitor.checkOutReader();
				} catch (InterruptedException e) {
					
				}
			}
			device.monitor.checkOutReader();
		} catch (InterruptedException e) {
			
		}
		
		if (!useCov) {
			return;
		}
		
		if (sf != null) {
			try {
				sf.get();
			} catch (BACnetException e) {
				LOGGER.debug("", e);
			}
		}
		
		enc = null;
		try {
			device.monitor.checkInReader();
			if (device.remoteDevice != null) {
				try {
					device.conn.monitor.checkInReader();
					if (device.conn.localDevice != null) {
						try {
							enc = RequestUtils.readProperty(device.conn.localDevice, device.remoteDevice, oid, pid, null);
						} catch (BACnetException e) {
							LOGGER.debug("", e);
						}
					}
					device.conn.monitor.checkOutReader();
				} catch (InterruptedException e) {
					
				}
			}
			device.monitor.checkOutReader();
		} catch (InterruptedException e) {
			
		}
		
		if (enc != null) {
			updateValue(enc);
		}
	}
	
	@Override
	protected void remove() {
		super.remove();
		device.objects.remove(this);
	}
	
	private void makeDiscoverAction() {
		Action act = new Action(Permission.READ, new Handler<ActionResult>() {
			@Override
			public void handle(ActionResult event) {
				addProperties();
			}
		});
		Node anode = node.getChild(ACTION_DISCOVER_PROPERTIES, true);
		if (anode == null) {
			node.createChild(ACTION_DISCOVER_PROPERTIES, true).setAction(act).build().setSerializable(false);
		} else {
			anode.setAction(act);
		}
	}
	
	private void makeAddPropertyAction() {
		Action act = new Action(Permission.READ, new Handler<ActionResult>() {
			@Override
			public void handle(ActionResult event) {
				addProperty(event);
			}
		});
		act.addParameter(new Parameter("Property Identifier", ValueType.makeEnum(Utils.getPropertyList())));
		Node anode = node.getChild(ACTION_ADD_PROPERTY, true);
		if (anode == null) {
			node.createChild(ACTION_ADD_PROPERTY, true).setAction(act).build().setSerializable(false);
		} else {
			anode.setAction(act);
		}
	}
	
	private void addProperty(ActionResult event) {
		String propStr = event.getParameter("Property Identifier").getString();
		PropertyIdentifier propid;
		try {
			propid = PropertyIdentifier.forName(propStr);
		} catch (Exception e) {
			return;
		}
		addProperty(propid);
	}
	
	private void makeEditAction() {
		Action act = new Action(Permission.READ, new Handler<ActionResult>() {
			@Override
			public void handle(ActionResult event) {
				edit(event);
			}
		});
		act.addParameter(new Parameter("Use COV", ValueType.BOOL, new Value(useCov)));
		act.addParameter(new Parameter("Enable Headless Polling", ValueType.BOOL, new Value(headlessPolling)));
		act.addParameter(new Parameter("Write Priority", ValueType.NUMBER, new Value(writePriority)));
		Node anode = node.getChild(ACTION_EDIT, true);
		if (anode == null) {
			node.createChild(ACTION_EDIT, true).setAction(act).build().setSerializable(false);
		} else {
			anode.setAction(act);
		}
	}
	
	private void edit(ActionResult event) {
		boolean newCovUse = event.getParameter("Use COV", ValueType.BOOL).getBool();
		boolean headless = event.getParameter("Enable Headless Polling", ValueType.BOOL).getBool();
		int newPriority = event.getParameter("Write Priority", ValueType.NUMBER).getNumber().intValue();
		if (newPriority < 1) {
			newPriority = 1;
		} else if (newPriority > 16) {
			newPriority = 16;
		}
		writePriority = newPriority;
		node.setRoConfig("Write Priority", new Value(writePriority));
		
		setCov(newCovUse);
		
		setHeadless(headless);
		
		makeEditAction();
		makeRelinquishAction();
	}
	
	private void setHeadless(boolean useHeadless) {
		headlessPolling = useHeadless;
		node.setRoConfig("Enable Headless Polling", new Value(headlessPolling));
		for (BacnetProperty prop: properties) {
			prop.updateHeadless();
		}
	}
	
	private void setCov(boolean newCovUse) {
		
		synchronized(lock) {
			if (useCov == newCovUse) {
				return;
			}
			
			if (newCovUse) {
				for (BacnetProperty property: properties) {
					if (PropertyIdentifier.presentValue.equals(pid) 
							|| PropertyIdentifier.statusFlags.equals(pid)
							|| (ObjectType.loop.equals(oid.getObjectType()) 
									&& (PropertyIdentifier.setpoint.equals(pid) 
											|| PropertyIdentifier.controlledVariableValue.equals(pid)))
							|| (ObjectType.pulseConverter.equals(oid.getObjectType()) 
									&& PropertyIdentifier.updateTime.equals(pid))) {
						if (device.unsubscribeProperty(property)) {
							property.subscribeCov();
						}
					}
				}
			} else {
				for (BacnetProperty property: properties) {
					if (property.getCovSubscribed()) {
						property.unsubscribeCov();
						device.subscribeProperty(property);
					}
				}
			}
			
			useCov = newCovUse;
			node.setRoConfig("Use COV", new Value(useCov));
		}
	}
	
	@Override
	protected void subscribe() {
		super.subscribe();
		ObjectType type = oid.getObjectType();
		device.subscribeProperty(hiddenNameProp);
		if (Utils.isOneOf(type, ObjectType.binaryInput, ObjectType.binaryOutput, ObjectType.binaryValue)) {
			device.subscribeProperty(hiddenActiveTextProp);
			device.subscribeProperty(hiddenInactiveTextProp);
		} else if (Utils.isOneOf(type, ObjectType.multiStateInput, ObjectType.multiStateOutput,
				ObjectType.multiStateValue)) {
			device.subscribeProperty(hiddenStateTextProp);
		}
	}
	
	@Override
	protected void unsubscribe() {
		super.unsubscribe();
		ObjectType type = oid.getObjectType();
		device.unsubscribeProperty(hiddenNameProp);
		if (Utils.isOneOf(type, ObjectType.binaryInput, ObjectType.binaryOutput, ObjectType.binaryValue)) {
			device.unsubscribeProperty(hiddenActiveTextProp);
			device.unsubscribeProperty(hiddenInactiveTextProp);
		} else if (Utils.isOneOf(type, ObjectType.multiStateInput, ObjectType.multiStateOutput,
				ObjectType.multiStateValue)) {
			device.unsubscribeProperty(hiddenStateTextProp);
		}
	}
	
	@Override
	public void updateValue(Encodable value) {
		
		if (value instanceof BACnetError || value instanceof BaseError) {
			node.setValue(null);
			return;
		}
		
		ValueType vt = null;
		Value v = null;
		DataType type = getDataType();
		switch (type) {
		case BINARY: {
			vt = ValueType.makeBool(stateText.get(1), stateText.get(0));
			if (value instanceof com.serotonin.bacnet4j.type.primitive.Boolean) {
				v = new Value(((com.serotonin.bacnet4j.type.primitive.Boolean) value).booleanValue());
			} else if (value instanceof Enumerated) {
				int i = ((Enumerated) value).intValue();
				v = new Value(i == 1);
			}
			break;
		}
		case NUMERIC: {
			vt = ValueType.NUMBER;
			if (value instanceof SignedInteger) {
				v = new Value(((SignedInteger) value).bigIntegerValue());
			} else if (value instanceof Real) {
				v = new Value(((Real) value).floatValue());
			} else if (value instanceof com.serotonin.bacnet4j.type.primitive.Double) {
				v = new Value(((com.serotonin.bacnet4j.type.primitive.Double) value).doubleValue());
			}
			break;
		}
		case MULTISTATE: {
			vt = ValueType.makeEnum(stateText);
			if (value instanceof Enumerated) {
				int i = ((Enumerated) value).intValue();
				v = new Value(stateText.get(i));
			} else if (value instanceof UnsignedInteger) {
				int i  = ((UnsignedInteger) value).intValue();
				v = new Value(stateText.get(i));
			}
			break;
		}
		case OTHER: {
			Pair<ValueType, Value> vtandv = TypeUtils.parseEncodable(value);
			vt = vtandv.getLeft();
			v = vtandv.getRight();
			break;
		}
		}
		if (vt == null || v == null) {
			vt = ValueType.STRING;
			v = new Value(value.toString());
		}
		
		
		node.setValueType(vt);
		node.setValue(v);
	}
	
	public void updateProperty(Encodable value, PropertyIdentifier propid) {
		if (value == null || value instanceof BACnetError || value instanceof BaseError) {
			return;
		}
		if (PropertyIdentifier.objectName.equals(propid)) {
			node.setDisplayName(value.toString());
		} else if (PropertyIdentifier.stateText.equals(propid)) {
			SequenceOf<CharacterString> states = (SequenceOf<CharacterString>) value;
			ArrayList<String> newstates = new ArrayList<String>();
			for (CharacterString state : states) {
				newstates.add(state.getValue());
			}
			if (!newstates.isEmpty()) {
				stateText = newstates;
			}
		} else if (PropertyIdentifier.activeText.equals(propid)) {
			stateText.set(1, value.toString());
		} else if (PropertyIdentifier.inactiveText.equals(propid)) {
			stateText.set(0, value.toString());
		}
	}
	
	public DataType getDataType() {
		if (dataType == null) {
			dataType = Utils.getDataType(oid.getObjectType());
		}
		return dataType;
	}
	
	void addCovSub() {
		if (covSubCount == 0) {
			device.subscribeObjectCov(this);
		}
		covSubCount += 1;
	}
	
	void removeCovSub() {
		if (covSubCount <= 0) {
			covSubCount = 0;
			return;
		}
		covSubCount -= 1;
		if (covSubCount <= 0) {
			covSubCount = 0;
			device.unsubscribeObjectCov(this);
		}
	}
	
	public void covNotificationReceived(UnsignedInteger timeRemaining, SequenceOf<PropertyValue> listOfValues) {
		for (BacnetProperty prop: properties) {
			int index = findPropertyInSequence(listOfValues, prop.pid);
			if (index >= 0) {
				PropertyValue propval = listOfValues.get(index);
				prop.updateValue(propval.getValue());
			}
		}
	}
	
	private static int findPropertyInSequence(SequenceOf<PropertyValue> seq, PropertyIdentifier prop) {
		int i = 0;
		for (PropertyValue propval: seq) {
			if (propval.getPropertyIdentifier().equals(prop)) {
				return i;
			}
			i++;
		}
		return -1;
	}
}