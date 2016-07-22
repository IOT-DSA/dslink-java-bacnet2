package bacnet.properties;

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

import com.serotonin.bacnet4j.type.Encodable;
import com.serotonin.bacnet4j.type.constructed.StatusFlags;
import com.serotonin.bacnet4j.type.enumerated.PropertyIdentifier;
import com.serotonin.bacnet4j.type.primitive.ObjectIdentifier;

import bacnet.LocalBacnetPoint;

public class LocalStatusFlagsProperty extends LocalBacnetProperty {
	private static final Logger LOGGER;

	static {
		LOGGER = LoggerFactory.getLogger(LocalUnitsProperty.class);
	}

	static final String ATTRIBUTE_STATUS_FLAGS = "status flags";
	static final String ATTRIBUTE_STATUS_FLAG_INALARM = "inAlarm";
	static final String ATTRIBUTE_STATUS_FLAG_FAULT = "fault";
	static final String ATTRIBUTE_STATUS_FLAG_OVERRIDDEN = "overridden";
	static final String ATTRIBUTE_STATUS_FLAG_OUTOFSERVICE = "outOfService";

	static final String ACTION_EDIT = "edit";

	StatusFlags flags;
	boolean inAlarm = false;
	boolean fault = false;
	boolean overridden = false;
	boolean outOfService = false;

	public LocalStatusFlagsProperty(LocalBacnetPoint point, Node parent, Node node) {
		super(point, parent, node);

	}

	public LocalStatusFlagsProperty(ObjectIdentifier oid, PropertyIdentifier pid, LocalBacnetPoint point, Node parent,
			Node node) {
		super(oid, pid, point, parent, node);

		bacnetObj.writeProperty(PropertyIdentifier.statusFlags,
				new StatusFlags(inAlarm, fault, overridden, outOfService));
		makeEditAction();
	}

	protected void makeEditAction() {
		Action act = new Action(Permission.READ, new EditHandler());
		act.addParameter(new Parameter(ATTRIBUTE_STATUS_FLAG_INALARM, ValueType.BOOL,
				node.getAttribute(ATTRIBUTE_STATUS_FLAG_INALARM)));
		act.addParameter(new Parameter(ATTRIBUTE_STATUS_FLAG_FAULT, ValueType.BOOL,
				node.getAttribute(ATTRIBUTE_STATUS_FLAG_FAULT)));
		act.addParameter(new Parameter(ATTRIBUTE_STATUS_FLAG_OVERRIDDEN, ValueType.BOOL,
				node.getAttribute(ATTRIBUTE_STATUS_FLAG_OVERRIDDEN)));
		act.addParameter(new Parameter(ATTRIBUTE_STATUS_FLAG_OUTOFSERVICE, ValueType.BOOL,
				node.getAttribute(ATTRIBUTE_STATUS_FLAG_OUTOFSERVICE)));

		Node editNode = node.getChild(ACTION_EDIT);
		if (editNode == null)
			node.createChild(ACTION_EDIT).setAction(act).build().setSerializable(false);
		else
			editNode.setAction(act);
	}

	private class EditHandler implements Handler<ActionResult> {

		@Override
		public void handle(ActionResult event) {
			inAlarm = event.getParameter(ATTRIBUTE_STATUS_FLAG_INALARM, ValueType.BOOL).getBool();
			fault = event.getParameter(ATTRIBUTE_STATUS_FLAG_FAULT, ValueType.BOOL).getBool();
			overridden = event.getParameter(ATTRIBUTE_STATUS_FLAG_OVERRIDDEN, ValueType.BOOL).getBool();
			outOfService = event.getParameter(ATTRIBUTE_STATUS_FLAG_OUTOFSERVICE, ValueType.BOOL).getBool();

			flags = new StatusFlags(inAlarm, fault, overridden, outOfService);
			bacnetObj.writeProperty(PropertyIdentifier.statusFlags, flags);

			String strFlags = "inAlarm: " + inAlarm + " fault: " + fault + " overridden: " + overridden
					+ " outOfService: " + outOfService;
			node.setValue(new Value(strFlags));

			node.setAttribute(ATTRIBUTE_STATUS_FLAG_INALARM, new Value(inAlarm));
			node.setAttribute(ATTRIBUTE_STATUS_FLAG_FAULT, new Value(fault));
			node.setAttribute(ATTRIBUTE_STATUS_FLAG_OVERRIDDEN, new Value(overridden));
			node.setAttribute(ATTRIBUTE_STATUS_FLAG_OUTOFSERVICE, new Value(outOfService));
		}
	}

	@Override
	public void updatePropertyValue(Encodable enc) {
		if (enc instanceof StatusFlags) {
			flags = (StatusFlags) enc;
			inAlarm = flags.isInAlarm();
			fault = flags.isFault();
			overridden = flags.isOverridden();
			outOfService = flags.isOutOfService();
			String strFlags = "inAlarm: " + inAlarm + " fault: " + fault + " overridden: " + overridden
					+ " outOfService: " + outOfService;
			node.setValue(new Value(strFlags));
		}

	}

}
