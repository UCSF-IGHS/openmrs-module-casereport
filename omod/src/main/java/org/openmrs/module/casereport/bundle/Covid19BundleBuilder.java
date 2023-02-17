package org.openmrs.module.casereport.bundle;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Date;
import java.util.List;
import java.util.UUID;
import java.util.stream.Collectors;

import ca.uhn.fhir.context.FhirContext;
import ca.uhn.fhir.rest.api.server.IBundleProvider;
import ca.uhn.fhir.rest.param.ReferenceAndListParam;
import org.hl7.fhir.r4.model.Bundle;
import org.hl7.fhir.r4.model.DateTimeType;
import org.hl7.fhir.r4.model.Encounter;
import org.hl7.fhir.r4.model.Observation;
import org.hl7.fhir.r4.model.Reference;
import org.hl7.fhir.r4.model.ResourceType;
import org.hl7.fhir.r4.model.ServiceRequest;
import org.hl7.fhir.r4.model.Specimen;
import org.hl7.fhir.r4.model.Task;
import org.openmrs.Obs;
import org.openmrs.Person;
import org.openmrs.api.context.Context;
import org.openmrs.module.casereport.Constants;
import org.openmrs.module.fhir2.api.translators.ConceptTranslator;

public class Covid19BundleBuilder {

	static FhirContext CTX = FhirContext.forR4();

	public static IBundleProvider getObservationRecords(ReferenceAndListParam subjectReference) {
		return BundleBuilderUtil.getObservationRecords(subjectReference, Constants.COVID19_TEST_REQUESTED_TYPE_CONCEPT_UUID,
				Constants.COVID19_LAB_ENCOUNTER_ENCOUNTER_TYPE_UUID);
	}

	public static void processC19TestRequest(Bundle bundle, Encounter encounter, Observation observation, Person person) {

		ServiceRequest serviceRequest = processServiceRequest(bundle, observation, encounter, person);
		processTask(bundle, observation, encounter, serviceRequest);
	}

	private static void processTask(Bundle bundle, Observation observation, Encounter encounter,
			ServiceRequest serviceRequest) {
		String taskUuid = UUID.randomUUID().toString();
		String taskFullUrl = "Task/" + taskUuid;
		Task task = new Task();
		task.setId(taskUuid);
		task.setStatus(Task.TaskStatus.REQUESTED);
		task.setIntent(Task.TaskIntent.ORDER);
		if (encounter != null) {
			task.addIdentifier().setSystem("OHRI_ENCOUNTER_UUID").setValue(encounter.getIdElement().getValue());
		} else {
			task.addIdentifier().setSystem("OHRI_OBSERVATION_UUID").setValue(observation.getIdElement().getValue());
		}
		String organizationReference = Context.getAdministrationService()
				.getGlobalProperty(Constants.GP_ORGANIZATION_REFERENCE);
		String labReference = Context.getAdministrationService().getGlobalProperty(Constants.GP_LAB_REFERENCE);
		task.setRequester(new Reference(organizationReference));
		task.setOwner(new Reference(labReference));
		task.setLastModified(new Date());
		task.addBasedOn(new Reference("ServiceRequest/" + serviceRequest.getId()));

		System.out.println("Covid19BundleBuilder:task - " + CTX.newJsonParser().encodeResourceToString(task));
		BundleBuilderUtil.addBundleEntry(bundle, taskFullUrl, task, taskFullUrl, Bundle.HTTPVerb.POST);
	}

	private static Specimen processSpecimen(Bundle bundle, Observation observation, List<Obs> observations) {
		String specimenUUID = UUID.randomUUID().toString();
		String specimenFullUrl = "Specimen/" + specimenUUID;
		Specimen specimen = new Specimen();
		specimen.setId(specimenUUID);

		String specimenId = observation.hasEncounter() ? observation.getEncounter().getId() : observation.getId();

		specimen.addIdentifier().setSystem("http://covid19laborder.org/specimen").setValue("SPECIMEN" + specimenId);

		Obs specimenTypeObs = BundleBuilderUtil.getObsHavingConceptUuid(observations,
				Constants.COVID19_TEST_REQUESTED_SPECIMEN_TYPE);
		ConceptTranslator conceptTranslator = Context.getRegisteredComponents(ConceptTranslator.class).get(0);
		if (specimenTypeObs != null) {
			specimen.setType(conceptTranslator.toFhirResource(specimenTypeObs.getValueCoded()));
		}

		if (observation.hasSubject()) {
			specimen.setSubject(observation.getSubject());
		}

		Specimen.SpecimenCollectionComponent specimenCollectionInfo = new Specimen.SpecimenCollectionComponent();
		specimenCollectionInfo.addChild("collectedDateTime");
		specimenCollectionInfo.setCollected(new DateTimeType(new Date()));
		specimen.setCollection(specimenCollectionInfo);
		System.out.println("Covid19BundleBuilder:specimen - " + CTX.newJsonParser().encodeResourceToString(specimen));
		BundleBuilderUtil.addBundleEntry(bundle, specimenFullUrl, specimen, specimenFullUrl, Bundle.HTTPVerb.PUT);
		return specimen;
	}

	private static ServiceRequest processServiceRequest(Bundle bundle, Observation observation, Encounter encounter,
			Person person) {

		String serviceRequestUUID = UUID.randomUUID().toString();
		String serviceRequestFullUrl = "ServiceRequest/" + serviceRequestUUID;
		ServiceRequest serviceRequest = new ServiceRequest();
		serviceRequest.setId(serviceRequestUUID);
		if (observation.hasSubject()) {
			serviceRequest.setSubject(observation.getSubject());
		}
		if (observation.hasEncounter()) {
			serviceRequest.setEncounter(observation.getEncounter());
		}
		List<Bundle.BundleEntryComponent> practitioners = bundle.getEntry().stream()
				.filter(b -> b.getResource().getResourceType().equals(ResourceType.Practitioner))
				.collect(Collectors.toList());
		if (practitioners != null) {
			for (Bundle.BundleEntryComponent practitioner : practitioners) {
				System.out.println("Covid19BundleBuilder:practitioner.getFullUrl() - " + practitioner.getFullUrl());
				serviceRequest.setRequester(new Reference(practitioner.getFullUrl()));
			}
		}
		serviceRequest.setStatus(ServiceRequest.ServiceRequestStatus.ACTIVE);
		serviceRequest.setIntent(ServiceRequest.ServiceRequestIntent.INSTANCEORDER);

		org.openmrs.Encounter openMrsEncounter = Context.getEncounterService()
				.getEncounterByUuid(encounter.getIdElement().getValue());
		System.out.println("Covid19BundleBuilder:openMrsEncounter - " + openMrsEncounter);

		String organizationName = Context.getAdministrationService().getGlobalProperty(Constants.GP_ORGANIZATION_NAME);
		serviceRequest.addIdentifier().setSystem("http://covid19laborder.org/order")
				.setValue("ORDERNR" + openMrsEncounter.getId());
		serviceRequest.addIdentifier().setSystem("OHRI_ENCOUNTER_UUID").setValue(openMrsEncounter.getUuid());

		List<Obs> observations = Context.getObsService()
				.getObservations(Arrays.asList(person), Arrays.asList(openMrsEncounter), null, null, null,
						null, null, null, null, null, null, false, null);

		ConceptTranslator conceptTranslator = Context.getRegisteredComponents(ConceptTranslator.class).get(0);
		Obs testRequestedTypeObs = BundleBuilderUtil.getObsHavingConceptUuid(observations,
				Constants.COVID19_TEST_REQUESTED_TYPE_CONCEPT_UUID);
		if (testRequestedTypeObs != null) {
			serviceRequest.setCode(conceptTranslator.toFhirResource(testRequestedTypeObs.getValueCoded()));
		}

		Obs testRequestedReasonObs = BundleBuilderUtil.getObsHavingConceptUuid(observations,
				Constants.COVID19_TEST_REQUESTED_REASON_CONCEPT_UUID);
		if (testRequestedReasonObs != null) {
			serviceRequest.addReasonCode(conceptTranslator.toFhirResource(testRequestedReasonObs.getValueCoded()));
		}

		Obs testRequestedNoteObs = BundleBuilderUtil.getObsHavingConceptUuid(observations,
				Constants.COVID19_TEST_REQUESTED_NOTE_CONCEPT_UUID);
		if (testRequestedNoteObs != null) {
			serviceRequest.addNote().setText(testRequestedNoteObs.getValueText());
		}

		Obs orderDate = BundleBuilderUtil.getObsHavingConceptUuid(observations, Constants.DATE_TEST_ORDERED_CONCEPT_UUID);
		if (orderDate != null) {
			serviceRequest.setAuthoredOn(orderDate.getValueDatetime());
		}

		if (encounter.hasLocation()) {
			List<Reference> locations = new ArrayList<>();
			for (Encounter.EncounterLocationComponent encounterLocation : encounter.getLocation()) {
				locations.add(encounterLocation.getLocation());
			}
			serviceRequest.setLocationReference(locations);
		}

		Specimen specimen = processSpecimen(bundle, observation, observations);
		serviceRequest.addSpecimen(new Reference("Specimen/" + specimen.getId()));

		System.out.println(
				"Covid19BundleBuilder:serviceRequest - " + CTX.newJsonParser().encodeResourceToString(serviceRequest));
		BundleBuilderUtil.addBundleEntry(bundle, serviceRequestFullUrl, serviceRequest, serviceRequestFullUrl,
				Bundle.HTTPVerb.PUT);

		return serviceRequest;
	}
}
