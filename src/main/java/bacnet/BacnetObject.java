package bacnet;

import org.dsa.iot.dslink.node.Node;
import org.dsa.iot.dslink.node.Permission;
import org.dsa.iot.dslink.node.actions.Action;
import org.dsa.iot.dslink.node.actions.ActionResult;
import org.dsa.iot.dslink.node.value.Value;
import org.dsa.iot.dslink.node.value.ValueType;
import org.dsa.iot.dslink.util.handler.Handler;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.serotonin.bacnet4j.exception.BACnetException;
import com.serotonin.bacnet4j.type.constructed.SequenceOf;
import com.serotonin.bacnet4j.type.enumerated.PropertyIdentifier;
import com.serotonin.bacnet4j.type.primitive.ObjectIdentifier;
import com.serotonin.bacnet4j.util.RequestUtils;

public class BacnetObject extends BacnetProperty {
	private static final Logger LOGGER = LoggerFactory.getLogger(BacnetObject.class);
	
	static final String ACTION_DISCOVER_PROPERTIES = "discover properties";
		
	BacnetObject(BacnetDevice device, Node node, ObjectIdentifier oid) {
		super(device, node, oid, PropertyIdentifier.presentValue);
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
		addProperties();
		makeDiscoverAction();
	}
	
	private void addProperties() {
		SequenceOf<PropertyIdentifier> proplist = getPropertyList();
		if (proplist == null) {
			return;
		}
		for (PropertyIdentifier propid: proplist) {
			addProperty(propid);
		}
	}
	
	
	private void addProperty(PropertyIdentifier propid) {
		String name = propid.toString();
		if (name.matches("^\\d+$") || node.hasChild(name)) {
			return;
		}
		
		Node child = node.createChild(name, true).setValueType(ValueType.STRING).setValue(new Value("")).build();
		BacnetProperty bp = new BacnetProperty(device, child, oid, propid);
		bp.setup();
	}
	
	private SequenceOf<PropertyIdentifier> getPropertyList() {
		synchronized(device.lock) {
			if (device.remoteDevice == null) {
				return null;
			}
			synchronized(device.conn.lock) {
				if (device.conn.localDevice == null) {
					return null;
				}
				try {
					return (SequenceOf<PropertyIdentifier>) RequestUtils.sendReadPropertyAllowNull(device.conn.localDevice, device.remoteDevice, oid, PropertyIdentifier.propertyList);
				} catch (BACnetException e) {
					LOGGER.debug("", e);
				}
			}
		}
		return null;
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
}
