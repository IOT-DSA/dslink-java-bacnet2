package bacnet.properties;

import org.dsa.iot.dslink.node.Node;
import org.dsa.iot.dslink.node.value.Value;

import com.serotonin.bacnet4j.type.enumerated.PropertyIdentifier;
import com.serotonin.bacnet4j.type.primitive.ObjectIdentifier;

import bacnet.LocalBacnetPoint;

public class LocalNumberOfStatesProperty extends LocalUnsignedIntegerProperty {

	public LocalNumberOfStatesProperty(LocalBacnetPoint point, Node parent, Node node) {
		super(point, parent, node);
	}
	
	public LocalNumberOfStatesProperty(ObjectIdentifier oid, PropertyIdentifier pid, LocalBacnetPoint point, Node parent, Node node, int def){
		super(oid, pid, point, parent, node, false);
		node.setValue(new Value(def));
		set(new Value(def));
	}
	
	@Override
	public void set(Value newVal) {
		super.set(newVal);
		int n = newVal.getNumber().intValue();
		for (int i=0; i<n; i++) {
			if (bacnetPoint.getUnitsDescription().size() < i+1) {
				bacnetPoint.getUnitsDescription().add(Integer.toString(i));
			}
		}
		while (bacnetPoint.getUnitsDescription().size() > n) {
			bacnetPoint.getUnitsDescription().remove(n);
		}
		
		LocalBacnetProperty pvalProp = bacnetPoint.getProperty(PropertyIdentifier.presentValue);
		if (pvalProp instanceof LocalUnsignedIntegerProperty) {
			((LocalUnsignedIntegerProperty) pvalProp).update();
		}
		
		LocalBacnetProperty relinqProp = bacnetPoint.getProperty(PropertyIdentifier.relinquishDefault);
		if (relinqProp instanceof LocalUnsignedIntegerProperty) {
			((LocalUnsignedIntegerProperty) relinqProp).update();
		}
		node.setAttribute(propertyId.toString(), newVal);
	}

}
