package bacnet;

import java.util.List;
import java.util.Map;

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


import com.serotonin.bacnet4j.exception.BACnetServiceException;
import com.serotonin.bacnet4j.obj.BACnetObject;
import com.serotonin.bacnet4j.obj.ObjectProperties;
import com.serotonin.bacnet4j.obj.PropertyTypeDefinition;
import com.serotonin.bacnet4j.type.Encodable;
import com.serotonin.bacnet4j.type.enumerated.ObjectType;
import com.serotonin.bacnet4j.type.enumerated.PropertyIdentifier;
import com.serotonin.bacnet4j.type.primitive.ObjectIdentifier;

import bacnet.properties.LocalBacnetProperty;


public abstract class EditablePoint {
	private static final Logger LOGGER;
	static {
		LOGGER = LoggerFactory.getLogger(EditablePoint.class);
	}
	
	static final String ACTION_REMOVE = "remove";
	static final String ACTION_EDIT = "edit";
	static final String ACTION_MAKE_COPY = "make copy";
	
	static final String PROPERTY_DATA_TYPE = "dataType";
	static final String PROPERTY_OBJECT_NAME = "objectName";
	static final String PROPERTY_PRESENT_VALUE = "present value";
	static final String PROPERTY_UNITS = "units";
	static final String PROPERTY_RELINQUISH_ALL = "relinquish all";
	static final String PROPERTY_RELINQUISH = "relinquish";
	
	EditableFolder folder;
    Node parent;
	Node node;

	BACnetObject bacnetObj;
	
	private int defaultPriority;
	ObjectIdentifier objectId;
//	PropertyIdentifier propertyId;
	int id;
	Map<PropertyIdentifier, LocalBacnetProperty> propertyIdToLocalProperty; 
	
	public EditablePoint(EditableFolder folder, Node parent){
		this.folder = folder;
		this.parent = parent;
	}
	
	public EditablePoint(EditableFolder folder, Node parent, Node node){
        this(folder, parent);
        
		this.node = node;
		
		this.makePointActions();
	}
	
	protected void makePointActions() {
		Action act = new Action(Permission.READ, new RemoveHandler());
		Node anode = node.getChild(ACTION_REMOVE);
		if (anode == null)
			node.createChild(ACTION_REMOVE).setAction(act).build().setSerializable(false);
		else
			anode.setAction(act);

		act = new Action(Permission.READ, new EditHandler());
		act.addParameter(new Parameter("name", ValueType.STRING, new Value(node.getName())));
		act.addParameter(new Parameter("object type",
				ValueType.makeEnum(Utils.enumNames(ObjectType.class)),
				node.getAttribute("object type")));
		act.addParameter(
				new Parameter("object instance number", ValueType.NUMBER, node.getAttribute("object instance number")));
		act.addParameter(new Parameter("use COV", ValueType.BOOL, node.getAttribute("use COV")));
		act.addParameter(new Parameter("settable", ValueType.BOOL, node.getAttribute("settable")));
		act.addParameter(new Parameter("default priority", ValueType.NUMBER, node.getAttribute("default priority")));
		anode = node.getChild(ACTION_EDIT);
		if (anode == null)
			node.createChild(ACTION_EDIT).setAction(act).build().setSerializable(false);
		else
			anode.setAction(act);

		act = new Action(Permission.READ, new CopyHandler());
		act.addParameter(new Parameter("name", ValueType.STRING));
		anode = node.getChild(ACTION_MAKE_COPY);
		if (anode == null) {
			node.createChild(ACTION_MAKE_COPY).setAction(act).build().setSerializable(false);
		} else {
			anode.setAction(act);
		}

		makeSetAction(node, -1);			


	}
	
	protected void makeSetAction(Node node, int priority) {
		node.setWritable(Writable.WRITE);
		node.getListener().setValueHandler(new RawSetHandler(priority));
	}
	
	public boolean isPresentValueRequired(){
		ObjectType ot = objectId.getObjectType();
		// only initialize the required properties
		List<PropertyTypeDefinition> defs = ObjectProperties.getRequiredPropertyTypeDefinitions(ot);
		for (PropertyTypeDefinition def: defs){
			PropertyIdentifier pid = def.getPropertyIdentifier();
			if (pid.equals(PropertyIdentifier.presentValue)){
				return true;
			}
		}
		
		return false;
	}
	private class RawSetHandler implements Handler<ValuePair> {
		private int priority;

		RawSetHandler(int p) {
			this.priority = p;
		}

		public void handle(ValuePair event) {
			if (!event.isFromExternalSource()) {
				return;
			}
			Value newVal = event.getCurrent();
			int newProirity = (priority > -1) ? priority : defaultPriority;
			handleSet(newVal, newProirity, true);
			
			Node presentValueNode = node.getChild(PROPERTY_PRESENT_VALUE);
			presentValueNode.setValue(newVal);
		}
	}


	protected class CopyHandler implements Handler<ActionResult> {
		public void handle(ActionResult event) {
			String name = event.getParameter("name", ValueType.STRING).getString();
			Node newnode = parent.createChild(name).build();
			newnode.setAttribute("restore type", new Value("point"));

			makeCopy();
		}
	}
	
	protected class EditHandler implements Handler<ActionResult> {
		public void handle(ActionResult event) {
			String newname = event.getParameter("name", ValueType.STRING).getString();
			if (newname != null && newname.length() > 0 && !newname.equals(node.getName())) {
				parent.removeChild(node);
				node = parent.createChild(newname).build();
			}
			
			setupNode();

		}
	}
	
	protected ObjectIdentifier getObjectIdentifier(){
		ObjectType objectType = Utils.parseObjectType(node.getAttribute("object type").getString());
		int instNum = node.getAttribute("object instance number").getNumber().intValue();
		 ObjectIdentifier oid = new ObjectIdentifier(objectType, instNum);
		 return oid;
	}

	protected class RemoveHandler implements Handler<ActionResult> {
		public void handle(ActionResult event) {
			node.clearChildren();
			parent.removeChild(node);

			ObjectIdentifier oid = getObjectIdentifier();
			try {
				folder.getLocalDevice().removeObject(oid);
			} catch (BACnetServiceException e) {
                LOGGER.debug("error :", e);
			}
		}
	}


	protected class RelinquishHandler implements Handler<ActionResult> {
		private int priority;

		RelinquishHandler(int p) {
			priority = p;
		}

		public void handle(ActionResult event) {

		}
	}

	protected class RelinquishAllHandler implements Handler<ActionResult> {
		public void handle(ActionResult event) {
			
		}
	}
	
	protected void relinquish(int priority) {

	}
	
	protected abstract void setupNode();
	protected abstract void makeCopy();
	protected abstract void handleSet(Value val, int priority, boolean isRaw);
	
	public BACnetObject getBacnetObj() {
		return bacnetObj;
	}

	public void setBacnetObj(BACnetObject bacnetObj) {
		this.bacnetObj = bacnetObj;
	}
	
	public Node getNode() {
		return node;
	}
	
	public LocalBacnetProperty getProperty(PropertyIdentifier pid) {

		return propertyIdToLocalProperty.get(pid);
	}

	public void updatePointValue(Encodable enc) {

	}
}
