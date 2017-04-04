package bacnet;

import com.serotonin.bacnet4j.type.Encodable;
import com.serotonin.bacnet4j.type.enumerated.PropertyIdentifier;
import com.serotonin.bacnet4j.type.primitive.ObjectIdentifier;

public class HiddenProperty extends BacnetProperty {

	HiddenProperty(BacnetDevice device, BacnetObject object, ObjectIdentifier oid, PropertyIdentifier pid) {
		super(device, object, null, oid, pid);
	}
	
	@Override
	protected void setup() {
		//no-op
	}
	
	@Override
	public void updateValue(Encodable value) {
		object.updateProperty(value, pid);
	}
}
