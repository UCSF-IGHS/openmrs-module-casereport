package org.openmrs.module.casereport.bundle;

import ca.uhn.fhir.rest.api.server.IBundleProvider;
import ca.uhn.fhir.rest.param.ReferenceAndListParam;
import org.openmrs.module.casereport.Constants;

public class ViralLoadBundleBuilder {

	public static IBundleProvider getObservationRecords(ReferenceAndListParam subjectReference) {
		return BundleBuilderUtil.getObservationRecords(subjectReference, Constants.DATE_TEST_ORDERED_CONCEPT_UUID,
				Constants.VIRAL_LOAD_ENCOUNTER_ENCOUNTER_TYPE_UUID);
	}
}
