package bacnet.properties;

import org.dsa.iot.dslink.node.Node;
import org.dsa.iot.dslink.node.value.Value;

import bacnet.LocalBacnetPoint;

import com.serotonin.bacnet4j.type.enumerated.PropertyIdentifier;
import com.serotonin.bacnet4j.type.primitive.ObjectIdentifier;

public class LocalBinaryStateTextProperty extends LocalCharacterStringProperty {

	boolean active;
	
	public LocalBinaryStateTextProperty(LocalBacnetPoint point, Node parent, Node node) {
		super(point, parent, node);

	}
	
	public LocalBinaryStateTextProperty(ObjectIdentifier oid, PropertyIdentifier pid, LocalBacnetPoint point, Node parent, Node node, String def) {
		super(oid, pid, point, parent, node);
		active = pid.equals(PropertyIdentifier.activeText);
		node.setValue(new Value(def));
		set(new Value(def));
	}
	
	@Override
	public void set(Value newVal) {
		super.set(newVal);
		
		if (bacnetPoint.getUnitsDescription().size() == 0) {
			String inactiveText = active ? "false" : newVal.getString();
			bacnetPoint.getUnitsDescription().add(inactiveText);
		} else if (!active) {
			bacnetPoint.getUnitsDescription().set(0, newVal.getString());
		}
		
		if (active) {
			if (bacnetPoint.getUnitsDescription().size() == 1) {
				bacnetPoint.getUnitsDescription().add(newVal.getString());
			} else {
				bacnetPoint.getUnitsDescription().set(1, newVal.getString());
			}
		}
		
		LocalBacnetProperty pvalProp = bacnetPoint.getProperty(PropertyIdentifier.presentValue);
		if (pvalProp instanceof LocalBinaryPVProperty) {
			((LocalBinaryPVProperty) pvalProp).update();
		}
		
		LocalBacnetProperty relinqProp = bacnetPoint.getProperty(PropertyIdentifier.relinquishDefault);
		if (relinqProp instanceof LocalBinaryPVProperty) {
			((LocalBinaryPVProperty) relinqProp).update();
		}
	}

}
