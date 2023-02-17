package org.openmrs.module.casereport.bundle;

import ca.uhn.fhir.rest.api.server.IBundleProvider;
import ca.uhn.fhir.rest.param.ReferenceAndListParam;
import org.openmrs.module.casereport.Constants;

public class Covid19AssessmentBundleBuilder {

	public static IBundleProvider getObservationRecords(ReferenceAndListParam subjectReference) {
		return BundleBuilderUtil.getObservationRecords(subjectReference, Constants.DATE_OF_EVENT_CONCEPT_UUID,
				Constants.COVID_ASSESSMENT_ENCOUNTER_TYPE_UUID);
	}
}
