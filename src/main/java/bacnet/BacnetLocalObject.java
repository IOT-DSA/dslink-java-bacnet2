package bacnet;

import java.util.HashMap;
import java.util.Map;
import org.dsa.iot.dslink.node.Node;
import org.dsa.iot.dslink.node.Permission;
import org.dsa.iot.dslink.node.actions.Action;
import org.dsa.iot.dslink.node.actions.ActionResult;
import org.dsa.iot.dslink.node.actions.Parameter;
import org.dsa.iot.dslink.node.value.Value;
import org.dsa.iot.dslink.node.value.ValueType;
import org.dsa.iot.dslink.util.handler.Handler;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.serotonin.bacnet4j.exception.BACnetServiceException;
import com.serotonin.bacnet4j.obj.BACnetObject;
import com.serotonin.bacnet4j.obj.BACnetObjectListener;
import com.serotonin.bacnet4j.obj.ObjectProperties;
import com.serotonin.bacnet4j.obj.ObjectPropertyTypeDefinition;
import com.serotonin.bacnet4j.type.Encodable;
import com.serotonin.bacnet4j.type.constructed.SequenceOf;
import com.serotonin.bacnet4j.type.enumerated.PropertyIdentifier;
import com.serotonin.bacnet4j.type.primitive.ObjectIdentifier;

public class BacnetLocalObject implements BACnetObjectListener {
	private static final Logger LOGGER = LoggerFactory.getLogger(BacnetLocalObject.class);
	
	static final String ACTION_REMOVE = "remove";
	static final String ACTION_ADD_PROPERTY = "add property";
	
	BacnetLocalDevice device;
	Node node;
	ObjectIdentifier oid;
	BACnetObject obj;
	final ReadWriteMonitor monitor = new ReadWriteMonitor();
	
	Map<PropertyIdentifier, BacnetLocalProperty> properties = new HashMap<PropertyIdentifier, BacnetLocalProperty>();
	
	BacnetLocalObject(BacnetLocalDevice device, Node node, ObjectIdentifier oid) {
		this.device = device;
		this.node = node;
		this.oid = oid;
	}
	
	void restoreLastSession() {
		init();
		if (node.getChildren() == null) {
			return;
		}
		for (Node child: node.getChildren().values()) {
			if (child.getAction() != null) {
				continue;
			}
			try {
				PropertyIdentifier propid = PropertyIdentifier.forName(child.getName());
				addProperty(propid, child);
			} catch (Exception e) {
				child.delete(false);
			}
		}
	}
	
	void init() {
		try {
			monitor.checkInWriter();
			initObj();
			monitor.checkOutWriter();
		} catch (InterruptedException e1) {
		}
		
		
		makeRemoveAction();
		
		try {
			monitor.checkInReader();
			if (obj != null) {
				try {
					SequenceOf<PropertyIdentifier> props = obj.readProperty(PropertyIdentifier.propertyList);
					for (PropertyIdentifier prop: props) {
						addProperty(prop);
					}
				} catch (BACnetServiceException | ClassCastException e) {
					LOGGER.debug("", e);
				}
			}
			monitor.checkOutReader();
		} catch (InterruptedException e1) {
		}
		
		for (ObjectPropertyTypeDefinition defn: ObjectProperties.getRequiredObjectPropertyTypeDefinitions(oid.getObjectType())) {
			addProperty(defn.getPropertyTypeDefinition().getPropertyIdentifier());
		}
		
		
		makeAddPropertyAction();
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
			child = node.createChild(name, true).setValueType(ValueType.STRING).setValue(null).build();
		}
		BacnetLocalProperty blp = new BacnetLocalProperty(this, child, propid);
		blp.init();
	}
	
	private void initObj() {
		try {
			device.conn.monitor.checkInReader();
			if (device.conn.localDevice != null) {
				obj = new BACnetObject(device.conn.localDevice, oid, node.getName());
				obj.addListener(this);
			}
			device.conn.monitor.checkOutReader();
		} catch (InterruptedException e) {
			
		}
	}
	
	public void onLocalDeviceChanged() {
		try {
			monitor.checkInWriter();
			obj = null;
			initObj();
			monitor.checkOutWriter();
		} catch (InterruptedException e) {	
		}
	}
	
	private void makeRemoveAction() {
		Action act = new Action(Permission.READ, new Handler<ActionResult>() {
			@Override
			public void handle(ActionResult event) {
				remove();
			}
		});
		Node anode = node.getChild(ACTION_REMOVE, true);
		if (anode == null) {
			node.createChild(ACTION_REMOVE, true).setAction(act).build().setSerializable(false);
		} else {
			anode.setAction(act);
		}
	}

	private void remove() {
		try {
			device.conn.monitor.checkInReader();
			if (device.conn.localDevice != null) {
				try {
					device.conn.localDevice.removeObject(oid);
				} catch (BACnetServiceException e) {
					LOGGER.debug("", e);
				}
			}
			device.conn.monitor.checkOutReader();
		} catch (InterruptedException e) {
			
		}
		node.delete(false);
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

	@Override
	public void propertyChange(PropertyIdentifier pid, Encodable oldValue, Encodable newValue) {
		BacnetLocalProperty prop = properties.get(pid);
		if (prop != null) {
			prop.update(newValue);
		}
	}

}
