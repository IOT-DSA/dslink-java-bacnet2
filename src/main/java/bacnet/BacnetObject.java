package bacnet;

import java.util.ArrayList;
import java.util.List;

import org.dsa.iot.dslink.node.Node;
import org.dsa.iot.dslink.node.Permission;
import org.dsa.iot.dslink.node.Writable;
import org.dsa.iot.dslink.node.actions.Action;
import org.dsa.iot.dslink.node.actions.ActionResult;
import org.dsa.iot.dslink.node.value.Value;
import org.dsa.iot.dslink.node.value.ValuePair;
import org.dsa.iot.dslink.node.value.ValueType;
import org.dsa.iot.dslink.util.handler.Handler;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.serotonin.bacnet4j.exception.BACnetException;
import com.serotonin.bacnet4j.service.confirmed.WritePropertyRequest;
import com.serotonin.bacnet4j.type.Encodable;
import com.serotonin.bacnet4j.type.constructed.SequenceOf;
import com.serotonin.bacnet4j.type.enumerated.ObjectType;
import com.serotonin.bacnet4j.type.enumerated.PropertyIdentifier;
import com.serotonin.bacnet4j.type.primitive.CharacterString;
import com.serotonin.bacnet4j.type.primitive.Enumerated;
import com.serotonin.bacnet4j.type.primitive.ObjectIdentifier;
import com.serotonin.bacnet4j.type.primitive.Real;
import com.serotonin.bacnet4j.type.primitive.SignedInteger;
import com.serotonin.bacnet4j.type.primitive.UnsignedInteger;
import com.serotonin.bacnet4j.util.RequestUtils;

public class BacnetObject extends BacnetProperty {
	private static final Logger LOGGER = LoggerFactory.getLogger(BacnetObject.class);
	
	static final String ACTION_DISCOVER_PROPERTIES = "discover properties";
	
	private DataType dataType = null;
	
	private List<String> stateText = new ArrayList<String>(2);
	
	private BacnetProperty hiddenStateTextProp;
	private BacnetProperty hiddenActiveTextProp;
	private BacnetProperty hiddenInactiveTextProp;
		
	BacnetObject(BacnetDevice device, Node node, ObjectIdentifier oid) {
		super(device, node, oid, PropertyIdentifier.presentValue);
		this.object = this;
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
					addProperty(propid);
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
		makeDiscoverAction();
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
		String name = propid.toString();
		if (name.matches("^\\d+$") || node.hasChild(name)) {
			return;
		}
		
		Node child = node.createChild(name, true).setValueType(ValueType.STRING).setValue(new Value("")).build();
		BacnetProperty bp = new BacnetProperty(device, this, child, oid, propid);
		bp.setup();
	}
	
	private SequenceOf<PropertyIdentifier> getPropertyList() {
		try {
			device.monitor.checkInReader();
			if (device.remoteDevice == null) {
				return null;
			}
			try {
				device.conn.monitor.checkInReader();
				if (device.conn.localDevice == null) {
					return null;
				}
				try {
					return (SequenceOf<PropertyIdentifier>) RequestUtils.sendReadPropertyAllowNull(device.conn.localDevice, device.remoteDevice, oid, PropertyIdentifier.propertyList);
				} catch (BACnetException e) {
					LOGGER.debug("", e);
				}
				device.conn.monitor.checkOutReader();
			} catch (InterruptedException e) {
				
			}
			device.monitor.checkOutReader();
		} catch (InterruptedException e) {
			
		}
		return null;
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
		Encodable enc = null;
		if (Utils.isOneOf(oid.getObjectType(), ObjectType.binaryOutput, ObjectType.binaryValue)) {
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
		
		try {
			device.monitor.checkInReader();
			if (device.remoteDevice == null) {
				return;
			}
			try {
				device.conn.monitor.checkInReader();
				if (device.conn.localDevice == null) {
					return;
				}
				device.conn.localDevice.send(device.remoteDevice, new WritePropertyRequest(oid, pid, null, enc, null));
				device.conn.monitor.checkOutReader();
			} catch (InterruptedException e) {
				
			}
			device.monitor.checkOutReader();
		} catch (InterruptedException e) {
			
		}
		
	}
	
	private void makeDiscoverAction() {
		Action act = new Action(Permission.READ, new Handler<ActionResult>(){
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
	
	@Override
	protected void subscribe() {
		super.subscribe();
		ObjectType type = oid.getObjectType();
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
		}
		case STRING: {
			vt = ValueType.STRING;
			v = new Value(value.toString());
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
		if (value == null) {
			return;
		}
		if (PropertyIdentifier.stateText.equals(propid)) {
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
}
