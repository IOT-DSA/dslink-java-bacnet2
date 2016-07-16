package bacnet.properties;

import java.util.Arrays;

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

import com.serotonin.bacnet4j.type.enumerated.EngineeringUnits;
import com.serotonin.bacnet4j.type.enumerated.PropertyIdentifier;
import com.serotonin.bacnet4j.type.primitive.ObjectIdentifier;

import bacnet.LocalBacnetPoint;

public class LocalUnitsProperty extends LocalBacnetProperty {
	private static final Logger LOGGER;

	static {
		LOGGER = LoggerFactory.getLogger(LocalUnitsProperty.class);
	}

	static final String ATTRIBUTE_ENGINEERING_UNITS = "engineering units";
	static final String ACTION_EDIT = "edit";

	EngineeringUnits units;

	public LocalUnitsProperty(LocalBacnetPoint point, Node parent, Node node) {
		super(point, parent, node);

	}
	
	public LocalUnitsProperty(ObjectIdentifier oid, PropertyIdentifier pid, LocalBacnetPoint point, Node parent, Node node){
		super(oid, pid, point, parent, node);
		
		bacnetObj.writeProperty(PropertyIdentifier.units, EngineeringUnits.degreeDaysCelsius);
		makeEditAction();
	}

	private  String[] enumeratedNames(){
		String valuesStr = Arrays.toString(EngineeringUnits.ALL);
		return valuesStr.substring(1, valuesStr.length() - 1).replace(" ", "").split(",");
	}
	
	protected void makeEditAction() {
		Action act = new Action(Permission.READ, new EditHandler());
		act.addParameter(
				new Parameter(ATTRIBUTE_ENGINEERING_UNITS, ValueType.makeEnum(enumeratedNames()),
						node.getAttribute(ATTRIBUTE_ENGINEERING_UNITS)));

		Node editNode = node.getChild(ACTION_EDIT);
		if (editNode == null)
			node.createChild(ACTION_EDIT).setAction(act).build().setSerializable(false);
		else
			editNode.setAction(act);
	}

	private class EditHandler implements Handler<ActionResult> {

		@Override
		public void handle(ActionResult event) {
			units = parseEngineeringUnits(
					event.getParameter(ATTRIBUTE_ENGINEERING_UNITS, ValueType.STRING).getString());

			bacnetObj.writeProperty(PropertyIdentifier.units, units);
			
			Value newVal = new Value(units.toString()); 
			node.setAttribute(ATTRIBUTE_ENGINEERING_UNITS, newVal);
			node.setValue(newVal);
		}
	}

	protected EngineeringUnits parseEngineeringUnits(String unitsString) {

        for (EngineeringUnits unit: EngineeringUnits.ALL){
        	if (unit.toString().equals(unitsString)){
        		return unit;
        	}
        }	

		return null;
	}
}
