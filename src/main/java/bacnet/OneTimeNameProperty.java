package bacnet;

import com.serotonin.bacnet4j.type.Encodable;
import com.serotonin.bacnet4j.type.enumerated.PropertyIdentifier;
import com.serotonin.bacnet4j.type.primitive.ObjectIdentifier;

public class OneTimeNameProperty extends HiddenProperty {

	OneTimeNameProperty(BacnetDevice device, BacnetObject object, ObjectIdentifier oid) {
		super(device, object, oid, PropertyIdentifier.objectName);
	}
	
	@Override
	public void updateValue(Encodable value) {
		device.unsubscribeProperty(this);
		super.updateValue(value);
	}

}
