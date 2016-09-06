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

	static final String RESTORE_TYPE = "restore type";
	static final String RESTORE_EDITABLE_POINT = "editable point";

	static final String PROPERTY_DATA_TYPE = "dataType";
	static final String PROPERTY_OBJECT_NAME = "objectName";
	static final int PRIORITY_NON_WRITABLE = -1;

	static final String ATTRIBUTE_NAME = "name";
	static final String ATTRIBUTE_OBJECT_TYPE = "object type";
	static final String ATTRIBUTE_OBJECT_INSTANCE_NUMBER = "object instance number";
	static final String ATTRIBUTE_USE_COV = "use COV";
	static final String ATTRIBUTE_SETTABLE = "settable";
	static final String ATTRIBUTE_DEFAULT_PRIORITY = "default priority";

	EditableFolder folder;
	Node parent;
	Node node;

	BACnetObject bacnetObj;

	private int defaultPriority;
	ObjectIdentifier objectId;
	// PropertyIdentifier propertyId;
	int id;
	Map<PropertyIdentifier, LocalBacnetProperty> propertyIdToLocalProperty;

	public EditablePoint(EditableFolder folder, Node parent) {
		this.folder = folder;
		this.parent = parent;
	}

	public EditablePoint(EditableFolder folder, Node parent, Node node) {
		this(folder, parent);

		this.node = node;

		this.makePointActions();
	}

	protected void makeRemoveAction() {
		Action act = new Action(Permission.READ, new RemoveHandler());
		Node actionNode = node.getChild(ACTION_REMOVE);
		if (actionNode == null)
			node.createChild(ACTION_REMOVE).setAction(act).build().setSerializable(false);
		else
			actionNode.setAction(act);
	}

	protected void makeEditAction() {
		Action act = new Action(Permission.READ, new EditHandler());
		act.addParameter(new Parameter(ATTRIBUTE_NAME, ValueType.STRING, new Value(node.getName())));
		act.addParameter(new Parameter(ATTRIBUTE_OBJECT_TYPE, ValueType.makeEnum(Utils.enumeratedObjectTypeNames())));
		act.addParameter(new Parameter(ATTRIBUTE_OBJECT_INSTANCE_NUMBER, ValueType.NUMBER,
				node.getAttribute(ATTRIBUTE_OBJECT_INSTANCE_NUMBER)));
		act.addParameter(new Parameter(ATTRIBUTE_USE_COV, ValueType.BOOL, node.getAttribute(ATTRIBUTE_USE_COV)));
		act.addParameter(new Parameter(ATTRIBUTE_SETTABLE, ValueType.BOOL, node.getAttribute(ATTRIBUTE_SETTABLE)));
		act.addParameter(new Parameter(ATTRIBUTE_DEFAULT_PRIORITY, ValueType.NUMBER,
				node.getAttribute(ATTRIBUTE_DEFAULT_PRIORITY)));
		Node actionNode = node.getChild(ACTION_EDIT);
		if (actionNode == null)
			node.createChild(ACTION_EDIT).setAction(act).build().setSerializable(false);
		else
			actionNode.setAction(act);

	}

	protected void makeCopyAction() {
		Action act = new Action(Permission.READ, new CopyHandler());
		act.addParameter(new Parameter(ATTRIBUTE_NAME, ValueType.STRING));
		Node actionNode = node.getChild(ACTION_MAKE_COPY);
		if (actionNode == null) {
			node.createChild(ACTION_MAKE_COPY).setAction(act).build().setSerializable(false);
		} else {
			actionNode.setAction(act);
		}
	}

	protected void makePointActions() {
		makeRemoveAction();
		makeEditAction();
		makeCopyAction();
		makeSetAction(node, PRIORITY_NON_WRITABLE);
	}

	protected void makeSetAction(Node node, int priority) {
		node.setWritable(Writable.WRITE);
		node.getListener().setValueHandler(new RawSetHandler(priority));
	}

	public boolean isPresentValueRequired() {
		ObjectType ot = objectId.getObjectType();
		// only initialize the required properties
		List<PropertyTypeDefinition> defs = ObjectProperties.getRequiredPropertyTypeDefinitions(ot);
		for (PropertyTypeDefinition def : defs) {
			PropertyIdentifier pid = def.getPropertyIdentifier();
			if (pid.equals(PropertyIdentifier.presentValue)) {
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

			Node presentValueNode = node.getChild(PropertyIdentifier.presentValue.toString());
			presentValueNode.setValue(newVal);
		}
	}

	protected class CopyHandler implements Handler<ActionResult> {
		public void handle(ActionResult event) {
			String name = event.getParameter("name", ValueType.STRING).getString();
			Node newnode = parent.createChild(name).build();
			newnode.setAttribute(RESTORE_TYPE, new Value(RESTORE_EDITABLE_POINT));

			makeCopy();
		}
	}

	protected class EditHandler implements Handler<ActionResult> {
		public void handle(ActionResult event) {
			String newname = event.getParameter(ATTRIBUTE_NAME, ValueType.STRING).getString();
			if (newname != null && newname.length() > 0 && !newname.equals(node.getName())) {
				parent.removeChild(node);
				node = parent.createChild(newname).build();
			}

			setupNode();

		}
	}

	protected ObjectIdentifier getObjectIdentifier() {
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

	protected static class RelinquishHandler implements Handler<ActionResult> {
		// private int priority;

		RelinquishHandler(int p) {
			// priority = p;
		}

		public void handle(ActionResult event) {

		}
	}

	protected static class RelinquishAllHandler implements Handler<ActionResult> {
		public void handle(ActionResult event) {

		}
	}

	protected void relinquish(int priority) {

	}

	protected abstract void setupNode();

	protected abstract void makeCopy();

	protected abstract void handleSet(Value val, int priority, boolean isRaw);

	public abstract void restoreLastSession();

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
