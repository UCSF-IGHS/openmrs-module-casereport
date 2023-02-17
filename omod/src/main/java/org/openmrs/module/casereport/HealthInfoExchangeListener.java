/**
 * This Source Code Form is subject to the terms of the Mozilla Public License,
 * v. 2.0. If a copy of the MPL was not distributed with this file, You can
 * obtain one at http://mozilla.org/MPL/2.0/. OpenMRS is also distributed under
 * the terms of the Healthcare Disclaimer located at http://openmrs.org/license.
 * Copyright (C) OpenMRS Inc. OpenMRS is a registered trademark and the OpenMRS
 * graphic logo is a trademark of OpenMRS Inc.
 */
package org.openmrs.module.casereport;

import javax.xml.bind.JAXBElement;
import javax.xml.transform.Result;
import java.io.File;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.TimeZone;
import java.util.UUID;

import ca.uhn.fhir.context.FhirContext;
import ca.uhn.fhir.rest.api.server.IBundleProvider;
import ca.uhn.fhir.rest.param.ReferenceAndListParam;
import ca.uhn.fhir.rest.param.ReferenceOrListParam;
import ca.uhn.fhir.rest.param.ReferenceParam;
import ca.uhn.fhir.rest.param.TokenAndListParam;
import ca.uhn.fhir.rest.param.TokenOrListParam;
import ca.uhn.fhir.rest.param.TokenParam;
import org.apache.commons.io.FileUtils;
import org.apache.commons.lang.StringUtils;
import org.apache.commons.lang3.SystemUtils;
import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.codehaus.jackson.map.ObjectMapper;
import org.dcm4chee.xds2.common.XDSConstants;
import org.dcm4chee.xds2.infoset.ihe.ObjectFactory;
import org.dcm4chee.xds2.infoset.ihe.ProvideAndRegisterDocumentSetRequestType;
import org.dcm4chee.xds2.infoset.rim.RegistryError;
import org.dcm4chee.xds2.infoset.rim.RegistryResponseType;
import org.hl7.fhir.instance.model.api.IBaseResource;
import org.hl7.fhir.r4.model.Bundle;
import org.hl7.fhir.r4.model.CodeableConcept;
import org.hl7.fhir.r4.model.Coding;
import org.hl7.fhir.r4.model.Composition;
import org.hl7.fhir.r4.model.Condition;
import org.hl7.fhir.r4.model.ContactPoint;
import org.hl7.fhir.r4.model.DateTimeType;
import org.hl7.fhir.r4.model.Encounter;
import org.hl7.fhir.r4.model.HumanName;
import org.hl7.fhir.r4.model.Identifier;
import org.hl7.fhir.r4.model.Observation;
import org.hl7.fhir.r4.model.Patient;
import org.hl7.fhir.r4.model.Reference;
import org.hl7.fhir.r4.model.Resource;
import org.hl7.fhir.r4.model.ResourceType;
import org.hl7.fhir.r4.model.ServiceRequest;
import org.hl7.fhir.r4.model.Specimen;
import org.hl7.fhir.r4.model.Task;
import org.hl7.fhir.r4.model.codesystems.ConditionClinical;
import org.hl7.fhir.r4.model.codesystems.ConditionVerStatus;
import org.openmrs.Concept;
import org.openmrs.Obs;
import org.openmrs.Person;
import org.openmrs.PersonAttribute;
import org.openmrs.api.APIException;
import org.openmrs.api.EncounterService;
import org.openmrs.api.ObsService;
import org.openmrs.api.context.Context;
import org.openmrs.module.casereport.CaseReport.Status;
import org.openmrs.module.casereport.api.CaseReportService;
import org.openmrs.module.casereport.api.CaseReportSubmittedEvent;
import org.openmrs.module.casereport.bundle.Covid19AssessmentBundleBuilder;
import org.openmrs.module.casereport.bundle.Covid19BundleBuilder;
import org.openmrs.module.casereport.bundle.Covid19ImmunizationBundleBuilder;
import org.openmrs.module.casereport.bundle.HivEnrolmentToCareBundleBuilder;
import org.openmrs.module.casereport.bundle.NewHivConditionBundleBuilder;
import org.openmrs.module.casereport.bundle.NewHivRxBundleBuilder;
import org.openmrs.module.casereport.bundle.PatientDiedBundleBuilder;
import org.openmrs.module.casereport.bundle.ViralLoadBundleBuilder;
import org.openmrs.module.casereport.web.FhirClient;
import org.openmrs.module.fhir2.FhirConstants;
import org.openmrs.module.fhir2.api.FhirEncounterService;
import org.openmrs.module.fhir2.api.FhirLocationService;
import org.openmrs.module.fhir2.api.FhirObservationService;
import org.openmrs.module.fhir2.api.FhirPatientService;
import org.openmrs.module.fhir2.api.FhirPractitionerService;
import org.openmrs.module.fhir2.api.search.param.ObservationSearchParams;
import org.openmrs.module.fhir2.api.search.param.SearchParameterMap;
import org.openmrs.module.fhir2.api.translators.ConceptTranslator;
import org.openmrs.module.fhir2.api.util.FhirUtils;
import org.openmrs.scheduler.TaskDefinition;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationEvent;
import org.springframework.context.ApplicationListener;
import org.springframework.stereotype.Component;
import org.springframework.ws.client.core.WebServiceMessageCallback;
import org.springframework.ws.client.core.WebServiceTemplate;
import org.springframework.xml.transform.StringResult;

/***
 * An instance of this class listens for event fired when a case report is
 * submitted so that it can generate and submit a CDA message to the HIE
 */
@Component
public class HealthInfoExchangeListener implements ApplicationListener<CaseReportSubmittedEvent> {

	static FhirContext CTX = FhirContext.forR4();

	protected final Log log = LogFactory.getLog(getClass());

	private final ObjectFactory objectFactory = new ObjectFactory();

	private final SimpleDateFormat sqlDateFormatter = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss");

	private final SimpleDateFormat localeDateformatter = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSSZ", Locale.US);

	@Autowired
	FhirEncounterService fhirEncounterService;

	@Autowired
	EncounterService encounterService;

	@Autowired
	ObsService obsService;

	@Autowired
	FhirPractitionerService fhirPractitionerService;

	@Autowired
	FhirLocationService fhirLocationService;

	@Autowired
	FhirObservationService fhirObservationService;

	@Autowired
	private FhirPatientService fhirPatientService;

	@Autowired
	private WebServiceTemplate webServiceTemplate;

	@Autowired
	private WebServiceMessageCallback messageCallback;

	@Autowired
	private ConceptTranslator conceptTranslator;

	/**
	 * @see ApplicationListener#onApplicationEvent(ApplicationEvent)
	 */
	@Override
	public void onApplicationEvent(CaseReportSubmittedEvent event) {
		localeDateformatter.setTimeZone(TimeZone.getTimeZone("GMT"));
		//Get the format
		String format = DocumentUtil.getCaseReportFormat();
		CaseReport caseReport = (CaseReport) event.getSource();
		String report = null;
		String response = null;
		JAXBElement rootElement = null;

		try {
			if (format.equals("FHIR")) {
				report = createFHIRMessage(caseReport);
				if (report != null) {
					response = FhirClient.postFhirMessage(report);
				} else {
					response = "";
				}
			} else {
				CaseReportForm form = new ObjectMapper().readValue(caseReport.getReportForm(), CaseReportForm.class);
				form.setReportUuid(caseReport.getUuid());
				form.setReportDate(caseReport.getDateCreated());
				ProvideAndRegisterDocumentSetRequestType docRequest = new ProvideAndRegisterDocGenerator(form).generate();
				rootElement = objectFactory.createProvideAndRegisterDocumentSetRequest(docRequest);

				Result out = new StringResult();
				webServiceTemplate.getMarshaller().marshal(rootElement, out);

				if (log.isDebugEnabled()) {
					log.debug("Saving Case report document to the file system.....");
				}
				response = postCDAMessage(rootElement);
			}

			//for demo, use the event date as resolution date instead of current datetime
			if (Context.getAdministrationService().getGlobalProperty(CaseReportWebConstants.ORG_NAME).toLowerCase()
					.contains("demo")) {
				CaseReportForm form = new ObjectMapper().readValue(caseReport.getReportForm(), CaseReportForm.class);
				DatedUuidAndValue triggerValue = form.getTriggers().get(0);
				Date resolutionDate = updateTriggerDate(triggerValue.getValue(), caseReport.getPatient().getId(), form);
				caseReport.setResolutionDate(resolutionDate);
			}

			if (response != null && response.indexOf("ERROR") == -1) {
				File docFile = DocumentUtil.getSubmittedCaseReportFile(caseReport);

				if (format.contentEquals("FHIR")) {
					FileUtils.writeStringToFile(docFile, report, DocumentConstants.ENCODING);
				} else {
					Result out = new StringResult();
					webServiceTemplate.getMarshaller().marshal(rootElement, out);
					FileUtils.writeStringToFile(docFile, out.toString(), DocumentConstants.ENCODING);
				}
				setCaseReportStatus(caseReport, true);
			} else {
				setCaseReportStatus(caseReport, false);
			}

			if (log.isDebugEnabled()) {
				log.debug("Case report document successfully saved to the file system");
			}

			if (log.isDebugEnabled()) {
				log.debug("Sending Case report document.....");
			}

		}
		catch (Exception e) {
			log.warn("An error occurred while submitting a case report document to the HIE \n" + e);
			setCaseReportStatus(caseReport, false);
			APIException rethrow;
			if (e instanceof APIException) {
				rethrow = (APIException) e;
			} else {
				rethrow = new APIException(e);
			}

			throw rethrow;
		}

	}

	private String createFHIRMessage(CaseReport caseReport) {

		try {
			// patient data
			String personUUID = caseReport.getPatient().getPerson().getUuid();
			org.hl7.fhir.r4.model.Patient patient = fhirPatientService.get(personUUID);
			setOfficialName(patient.getName());
			setOpenhieId(patient);
			List<Identifier> identifiersWithoutSystem = new ArrayList<>();

			for (Identifier identifier : patient.getIdentifier()) {
				if (identifier.hasType() && identifier.getType().getText().equals("OpenMRS ID")) {
					identifier.setSystem("http://openhie.org/fhir/hiv-casereporting/identifier/OpenMRSID");
				}

				if (!identifier.hasSystem()) {
					identifiersWithoutSystem.add(identifier);
				}
			}

			if (identifiersWithoutSystem.size() > 0) {
				patient.getIdentifier().removeAll(identifiersWithoutSystem);
			}

			List<ContactPoint> attributesWithoutSystem = new ArrayList<>();
			for (ContactPoint contactPoint : patient.getTelecom()) {
				String contactPointId = contactPoint.getId();
				PersonAttribute personAttribute = caseReport.getPatient().getAttribute("Telephone Number");
				if (personAttribute != null && personAttribute.getUuid().equals(contactPointId)) {
					contactPoint.setSystem(ContactPoint.ContactPointSystem.PHONE);
				}

				if (!contactPoint.hasSystem()) {
					attributesWithoutSystem.add(contactPoint);
				}
			}

			if (attributesWithoutSystem.size() > 0) {
				patient.getTelecom().removeAll(attributesWithoutSystem);
			}

			//patient.getIdentifier().add(setOrganizationId());

			// encounter data
			ReferenceAndListParam subjectReference = new ReferenceAndListParam();
			ReferenceParam subject = new ReferenceParam();
			subject.setValue(patient.getId());
			subjectReference.addValue(new ReferenceOrListParam().add(subject));

			// obs data
			SearchParameterMap theParams = new SearchParameterMap();
			theParams.addParameter(FhirConstants.PATIENT_REFERENCE_SEARCH_HANDLER, subjectReference);

			ObservationSearchParams observationSearchParams = new ObservationSearchParams();
			observationSearchParams.setPatient(subjectReference);

			// TODO pass in the correct search params
			CaseReportTrigger caseReportTrigger = caseReport.getCaseReportTriggerByName(
					Constants.HIV_CASE_REPORT_TRIGGER_NAME);
			CaseReportTrigger vlCaseReportTrigger = caseReport.getCaseReportTriggerByName("Viral Load Request");
			CaseReportTrigger c19TestCaseReportTrigger = caseReport.getCaseReportTriggerByName("COVID19 Lab Test Order");
			CaseReportTrigger c19CaseReportTrigger = caseReport.getCaseReportTriggerByName("New COVID19 Case");
			CaseReportTrigger newHivRxCaseReportTrigger = caseReport.getCaseReportTriggerByName("New HIV Treatment");
			CaseReportTrigger c19ImmunizationCaseReportTrigger = caseReport.getCaseReportTriggerByName(
					"New COVID19 Immunization");
			CaseReportTrigger hivPatientDiedCaseReportTrigger = caseReport.getCaseReportTriggerByName("HIV Patient Died");
			CaseReportTrigger c19AssessmentCaseReportTrigger = caseReport.getCaseReportTriggerByName(
					"New COVID19 Assessment");
			CaseReportTrigger hivEnrolmentToCareCaseReportTrigger = caseReport.getCaseReportTriggerByName(
					"HIV Enrolment Into Care");

			IBundleProvider observationRecords;
			if (caseReportTrigger != null) {
				TokenAndListParam conceptTokenAndListParam = new TokenAndListParam();
				TokenParam conceptTokenParam = new TokenParam(Constants.FINAL_HIV_TEST_RESULT);
				conceptTokenAndListParam.addAnd(conceptTokenParam);

				TokenAndListParam vcodedTokenAndListParam = new TokenAndListParam();
				TokenParam vcodedTokenParam = new TokenParam(Constants.POSITIVE_CONCEPT_UUID);
				vcodedTokenAndListParam.addAnd(vcodedTokenParam);

				observationSearchParams.setCode(conceptTokenAndListParam);
				observationSearchParams.setValueConcept(vcodedTokenAndListParam);

				observationRecords = fhirObservationService.searchForObservations(observationSearchParams);
			} else if (vlCaseReportTrigger != null) {
				observationRecords = ViralLoadBundleBuilder.getObservationRecords(subjectReference);
			} else if (c19TestCaseReportTrigger != null) {
				observationRecords = Covid19BundleBuilder.getObservationRecords(subjectReference);
			} else if (c19AssessmentCaseReportTrigger != null) {
				observationRecords = Covid19AssessmentBundleBuilder.getObservationRecords(subjectReference);
			} else if (hivEnrolmentToCareCaseReportTrigger != null) {
				observationRecords = HivEnrolmentToCareBundleBuilder.getObservationRecords(subjectReference);
			} else if (c19CaseReportTrigger != null) {
				TokenAndListParam conceptTokenAndListParam = new TokenAndListParam();
				TokenOrListParam tokenOrListParam = new TokenOrListParam();
				tokenOrListParam.add(Constants.RAPID_ANTIGEN_TEST_RESULT_CONCEPT_UUID);
				tokenOrListParam.add(Constants.DIAGNOSTIC_PCR_TEST_RESULT_CONCEPT_UUID);
				conceptTokenAndListParam.addAnd(tokenOrListParam);

				TokenAndListParam vcodedTokenAndListParam = new TokenAndListParam();
				TokenParam vcodedTokenParam = new TokenParam(Constants.POSITIVE_CONCEPT_UUID);
				vcodedTokenAndListParam.addAnd(vcodedTokenParam);

				observationSearchParams.setCode(conceptTokenAndListParam);
				observationSearchParams.setValueConcept(vcodedTokenAndListParam);

				observationRecords = fhirObservationService.searchForObservations(observationSearchParams);
			} else if (newHivRxCaseReportTrigger != null) {
				TokenAndListParam conceptTokenAndListParam = new TokenAndListParam();
				TokenParam conceptTokenParam = new TokenParam(Constants.ANTIRETROVIRAL_PLAN);
				conceptTokenAndListParam.addAnd(conceptTokenParam);

				TokenAndListParam vcodedTokenAndListParam = new TokenAndListParam();
				TokenParam vcodedTokenParam = new TokenParam(Constants.START_DRUGS);
				vcodedTokenAndListParam.addAnd(vcodedTokenParam);

				observationSearchParams.setCode(conceptTokenAndListParam);
				observationSearchParams.setValueConcept(vcodedTokenAndListParam);

				observationRecords = fhirObservationService.searchForObservations(observationSearchParams);
			} else if (c19ImmunizationCaseReportTrigger != null) {
				TokenAndListParam conceptTokenAndListParam = new TokenAndListParam();
				TokenParam conceptTokenParam = new TokenParam(Constants.COVID19_VACCINATION_GIVEN_CONCEPT_UUID);
				conceptTokenAndListParam.addAnd(conceptTokenParam);

				observationSearchParams.setCode(conceptTokenAndListParam);

				observationRecords = fhirObservationService.searchForObservations(observationSearchParams);
			} else if (hivPatientDiedCaseReportTrigger != null) {
				observationRecords = null;
			} else {
				observationRecords = fhirObservationService.searchForObservations(observationSearchParams);
			}

			Bundle bundle = new Bundle();
			bundle.setId(UUID.randomUUID().toString());
			bundle.setType(Bundle.BundleType.TRANSACTION);

			Composition composition = new Composition();
			composition.setId(UUID.randomUUID().toString());

			String patientFullUrl = "Patient/" + patient.getIdElement().getValue();
			bundle.addEntry().setFullUrl(patientFullUrl).setResource(patient).getRequest()
					.setUrl("Patient").setMethod(Bundle.HTTPVerb.POST);

			Composition.SectionComponent sectionComponent = new Composition.SectionComponent();
			sectionComponent.setTitle("Patient Info");
			sectionComponent.addEntry(new Reference(patientFullUrl));
			composition.addSection(sectionComponent);

			if (observationRecords != null && observationRecords.size() > 0) {
				System.out.println("observationRecords: " + observationRecords.size());
				// Add Observations
				sectionComponent = new Composition.SectionComponent();
				sectionComponent.setTitle("Observation Info");
				String observationFullUrl;
				for (IBaseResource resource : observationRecords.getAllResources()) {
					Observation observation = (Observation) resource;
					if (observation.hasEncounter()) {
						String encounterUuid = FhirUtils.referenceToId(observation.getEncounter().getReference()).orElse("");
						Encounter encounter = fhirEncounterService.get(encounterUuid);
						org.openmrs.Encounter omrsEncounter = encounterService.getEncounterByUuid(encounterUuid);
						String encounterTypeUuid = omrsEncounter.getEncounterType().getUuid();
						System.out.println("omrsEncounter.getEncounterType().getUuid(): " + encounterTypeUuid);
						System.out.println("encounter: " + encounter);
						if (encounter != null) {
							sectionComponent.setTitle("Encounter Info");
							org.openmrs.module.casereport.FhirUtils.processEncounter(bundle, sectionComponent,
									encounter, fhirEncounterService, fhirPractitionerService, fhirLocationService);
							if (vlCaseReportTrigger != null) {
								System.out.println("vlCaseReportTrigger != null");
								if (!encounterTypeUuid.equals(Constants.VIRAL_LOAD_ENCOUNTER_ENCOUNTER_TYPE_UUID)) {
									System.out.println(
											"encounterTypeUuid not equals to Constants.VIRAL_LOAD_ENCOUNTER_ENCOUNTER_TYPE");
									return null;
								}
								processVLRequest(bundle, encounter, observation,
										patientFullUrl, caseReport.getPatient().getPerson());
							}

							if (c19TestCaseReportTrigger != null) {
								System.out.println("c19TestCaseReportTrigger != null");
								if (!encounterTypeUuid.equals(Constants.COVID19_LAB_ENCOUNTER_ENCOUNTER_TYPE_UUID)) {
									System.out.println(
											"encounterTypeUuid not equals to Constants.COVID19_LAB_ENCOUNTER_ENCOUNTER_TYPE");
									return null;
								}
								Covid19BundleBuilder.processC19TestRequest(bundle, encounter, observation,
										caseReport.getPatient().getPerson());
							}

							if (c19ImmunizationCaseReportTrigger != null) {
								Covid19ImmunizationBundleBuilder.buildImmunizationBundle(bundle, encounter, observation,
										caseReport.getPatient().getPerson());
							}

							if (newHivRxCaseReportTrigger != null) {
								NewHivRxBundleBuilder.buildNewHivRxBundle(bundle, encounter, observation,
										caseReport.getPatient().getPerson());
							}

							if (hivEnrolmentToCareCaseReportTrigger != null) {
								HivEnrolmentToCareBundleBuilder.buildNewHivRxBundle(bundle, sectionComponent, encounter,
										observation,
										caseReport.getPatient());
							}
						}
					}

					if (caseReportTrigger != null) {
						processNewHivCondition(bundle, sectionComponent, caseReportTrigger, observation);
					}

					if (c19CaseReportTrigger != null) {
						processNewCovid19Condition(bundle, sectionComponent, caseReportTrigger, observation);
					}

					observationFullUrl = "Observation/" + observation.getIdElement().getValue();
					bundle.addEntry().setFullUrl(observationFullUrl).setResource(observation).getRequest()
							.setUrl("Observation").setMethod(Bundle.HTTPVerb.POST);
					sectionComponent.addEntry(new Reference(observationFullUrl));
				}
				composition.addSection(sectionComponent);
			} else {
				if (hivPatientDiedCaseReportTrigger != null) {
					PatientDiedBundleBuilder.buildPatienDiedBundle(bundle, caseReport.getPatient().getPerson(), patient);
				}
			}

			Bundle.BundleEntryComponent compositionBundleEntryComponent = new Bundle.BundleEntryComponent();
			compositionBundleEntryComponent.setFullUrl("Composition/" + composition.getIdElement().getValue())
					.setResource(composition).getRequest().setUrl("Composition").setMethod(Bundle.HTTPVerb.POST);

			if (caseReportTrigger != null || c19CaseReportTrigger != null) {
				bundle.getEntry().add(0, compositionBundleEntryComponent);
			}

			String jsonToSend = CTX.newJsonParser().encodeResourceToString(bundle);
			log.info(jsonToSend);
			System.out.println("\njsonToSend: \n" + jsonToSend + "\n");

			return jsonToSend;
		}
		catch (Exception e) {
			log.error("Exception -- " + e.getMessage());
			System.err.println("\nException: \n" + e + "\n");
		}
		return null;
	}

	private void processVLRequest(Bundle bundle, Encounter encounter, Observation dateTestOrderedObs,
			String patientFullUrl, Person person) {

		String specimenTypeConceptUUID = "9103ae1f-1461-4fcb-91d7-3705a40f5f5c";
		String reasonForVLConceptUUID = "86cc0cfe-bace-4969-94b6-d139f4971d13";
		String specimenIdConceptUUID = "159968AAAAAAAAAAAAAAAAAAAAAAAAAAAAAA";

		String uuid = encounter.getIdElement().getValue();
		org.openmrs.Encounter openMrsEncounter = encounterService.getEncounterByUuid(uuid);
		System.out.println("openMrsEncounter: " + openMrsEncounter);
		List<Obs> observations = obsService.getObservations(Arrays.asList(person), Arrays.asList(openMrsEncounter), null,
				null,
				null, null, null, null, null, null, null, false, null);
		System.out.println("observations: " + observations.size());
		System.out.println("observations: " + observations);
		Obs specimenTypeObs = null;
		Obs reasonForVLObs = null;
		Obs specimenIdObs = null;
		for (Obs obs : observations) {
			if (obs.getConcept().getUuid().equals(specimenTypeConceptUUID)) {
				specimenTypeObs = obs;
				System.out.println("specimenTypeObs: " + specimenTypeObs);
			}

			if (obs.getConcept().getUuid().equals(reasonForVLConceptUUID)) {
				reasonForVLObs = obs;
				System.out.println("reasonForVLObs: " + reasonForVLObs);
			}

			if (obs.getConcept().getUuid().equals(specimenIdConceptUUID)) {
				specimenIdObs = obs;
				System.out.println("specimenIdObs: " + specimenIdObs);
			}
		}

		Date today = new Date();
		String organizationReference = Context.getAdministrationService()
				.getGlobalProperty(Constants.GP_ORGANIZATION_REFERENCE);
		String labReference = Context.getAdministrationService().getGlobalProperty(Constants.GP_LAB_REFERENCE);

		String specimenUUID = UUID.randomUUID().toString();
		String specimenFullUrl = "Specimen/" + specimenUUID;
		Specimen specimen = new Specimen();
		specimen.setId(specimenUUID);
		CodeableConcept specimenType = new CodeableConcept();
		if (specimenTypeObs != null) {
			System.out.println("specimenTypeObs is null");
			specimenType.addCoding()
					.setCode(specimenTypeObs.getValueCoded().getName().getName())
					.setSystem("http://openhie.org/fhir/lab-integration/specimen-type-code");
		}
		specimen.setType(specimenType);
		Specimen.SpecimenCollectionComponent specimenCollectionInfo = new Specimen.SpecimenCollectionComponent();
		specimenCollectionInfo.addChild("collectedDateTime");
		specimenCollectionInfo.setCollected(new DateTimeType(today));
		specimen.setCollection(specimenCollectionInfo);
		addBundleEntry(bundle, specimenFullUrl, specimen, "Specimen");

		String serviceRequestUUID = UUID.randomUUID().toString();
		String serviceRequestFullUrl = "ServiceRequest/" + serviceRequestUUID;
		ServiceRequest serviceRequest = new ServiceRequest();
		serviceRequest.setId(serviceRequestUUID);
		serviceRequest.setStatus(ServiceRequest.ServiceRequestStatus.ACTIVE);
		serviceRequest.setIntent(ServiceRequest.ServiceRequestIntent.ORDER);
		serviceRequest.setSubject(new Reference(patientFullUrl));
		serviceRequest.setSpecimen(Arrays.asList(new Reference(specimenFullUrl)));
		serviceRequest.setAuthoredOn(dateTestOrderedObs.getValueDateTimeType().getValue());

		if (reasonForVLObs != null) {
			System.out.println("reasonForVLObs is null");
			serviceRequest
					.addReasonCode()
					.addCoding()
					.setSystem("http://openhie.org/fhir/lab-integration/vl-reason-code")
					.setCode(reasonForVLObs.getValueCoded().getName().getName());
		}

		Optional<Bundle.BundleEntryComponent> practitioner = bundle.getEntry().stream()
				.filter(b -> b.getResource().getResourceType().equals(ResourceType.Practitioner))
				.findFirst();
		System.out.println("practitioner: " + practitioner);
		if (practitioner != null) {
			practitioner.get().getFullUrl();
			serviceRequest.setRequester(new Reference(practitioner.get().getFullUrl()));
		}

		addBundleEntry(bundle, serviceRequestFullUrl, serviceRequest, "ServiceRequest");

		String taskFullUrl = "Task/" + uuid;
		Task task = new Task();
		task.setStatus(Task.TaskStatus.REQUESTED);
		task.setId(uuid);
		task.setIntent(Task.TaskIntent.ORDER);
		task.addIdentifier().setSystem("OHRI_ENCOUNTER_UUID").setValue(uuid);
		if (specimenIdObs != null) {
			System.out.println("practitioner is null");
			task.addIdentifier().setSystem("http://openhie.org/fhir/lab-integration/test-order-number")
					.setValue(String.valueOf(specimenIdObs.getValueNumeric().intValue()));
		}
		task.setRequester(new Reference(organizationReference));
		task.setOwner(new Reference(labReference));
		task.setLastModified(today);
		task.addBasedOn(new Reference(serviceRequestFullUrl));
		addBundleEntry(bundle, taskFullUrl, task, "Task");
	}

	private void addBundleEntry(Bundle bundle, String resourceFullUrl, Resource resource, String url) {
		bundle.addEntry()
				.setFullUrl(resourceFullUrl)
				.setResource(resource)
				.getRequest()
				.setUrl(url)
				.setMethod(Bundle.HTTPVerb.POST);
	}

	private void processNewCovid19Condition(Bundle bundle, Composition.SectionComponent sectionComponent,
			CaseReportTrigger caseReportTrigger, Observation observation) {
		Condition condition = new Condition();
		condition.setId(observation.getIdElement().getValue());
		condition.setSubject(observation.getSubject());
		if (observation.hasEncounter()) {
			condition.setEncounter(observation.getEncounter());
		}
		condition.setClinicalStatus(new CodeableConcept(new Coding().setCode(ConditionClinical.ACTIVE.toCode())));
		condition.setVerificationStatus(new CodeableConcept(new Coding().setCode(ConditionVerStatus.CONFIRMED.toCode())));
		condition.setRecordedDate(observation.getIssued());

		String conditionFullUrl = "Condition/" + condition.getIdElement().getValue();
		bundle.addEntry().setFullUrl(conditionFullUrl).setResource(condition).getRequest().setUrl("Condition")
				.setMethod(Bundle.HTTPVerb.PUT);
		sectionComponent.addEntry(new Reference(conditionFullUrl));
	}

	private void processNewHivCondition(Bundle bundle, Composition.SectionComponent sectionComponent,
			CaseReportTrigger caseReportTrigger, Observation observation) {
		NewHivConditionBundleBuilder.processNewHivCondition(bundle, sectionComponent, caseReportTrigger.getName(),
				observation);
	}

	private void setOfficialName(List<HumanName> humanNames) {
		for (HumanName humanName : humanNames) {
			if (humanName.hasUse() && humanName.getUse().equals(HumanName.NameUse.OFFICIAL)) {
				return;
			}
		}
		if (humanNames.get(0) != null) {
			humanNames.get(0).setUse(HumanName.NameUse.OFFICIAL);
		}
	}

	private void setOpenhieId(Patient patient) {
		Identifier identifier = new Identifier();
		identifier.setSystem("https://instantopenhie.org/client3");
		identifier.setValue(patient.getIdElement().getValue());
		patient.getIdentifier().add(identifier);
	}

	private Identifier setOrganizationId() {
		Identifier identifier = new Identifier();
		identifier.setSystem("urn:oid:" + DocumentUtil.getOrganizationOID());
		identifier.setUse(Identifier.IdentifierUse.OFFICIAL);
		return identifier;
	}

	private Date updateTriggerDate(Object value, Integer patientId, CaseReportForm form) throws Exception {
		String triggerName = (String) value;

		String date = null;
		if (triggerName.equals("HIV First CD4 Count")) {
			date = sqlDateFormatter.format(localeDateformatter.parse(form.getMostRecentCd4Count().getDate()));
		} else if (triggerName.equals("New HIV Case")) {
			date = sqlDateFormatter.format(localeDateformatter.parse(form.getMostRecentHivTest().getDate()));
		} else if (triggerName.equals("New HIV Treatment")) {
			Person person = new Person(patientId);
			Concept concept = new Concept(1255);
			List<Obs> obs = Context.getObsService().getObservationsByPersonAndConcept(person, concept);
			if (obs.size() > 0) {
				date = sqlDateFormatter.format(obs.get(0).getObsDatetime());
			}
		} else if (triggerName.equals("HIV Treatment Failure")) {
			date = sqlDateFormatter.format(localeDateformatter.parse(form.getMostRecentViralLoad().getDate()));
		} else if (triggerName.equals("HIV Patient Died")) {
			date = sqlDateFormatter.format(localeDateformatter.parse(form.getDeathdate()));
		} else {
			date = sqlDateFormatter.format(new Date());
		}
		if (date != null) {
			return sqlDateFormatter.parse(date);
		}
		return new Date();
	}

	private void setCaseReportStatus(CaseReport caseReport, boolean isSuccess) {
		CaseReportService crs = Context.getService(CaseReportService.class);
		if (isSuccess) {
			caseReport.setStatus(Status.SUBMITTED);
		} else {
			caseReport.setStatus(Status.NEW);
		}
		if (caseReport.getResolutionDate() == null) {
			caseReport.setResolutionDate(new Date());
		}
		crs.saveCaseReport(caseReport);
	}

	public String postCDAMessage(JAXBElement rootElement) throws Exception {
		String url = Context.getAdministrationService().getGlobalProperty(DocumentConstants.GP_OPENHIM_URL);
		Object response = webServiceTemplate.marshalSendAndReceive(url, rootElement, messageCallback);
		String lf = SystemUtils.LINE_SEPARATOR;
		RegistryResponseType regResp = ((JAXBElement<RegistryResponseType>) response).getValue();
		if (!XDSConstants.XDS_B_STATUS_SUCCESS.equals(regResp.getStatus())) {
			StringBuffer sb = new StringBuffer();
			if (regResp.getRegistryErrorList() != null && regResp.getRegistryErrorList().getRegistryError() != null) {
				for (RegistryError re : regResp.getRegistryErrorList().getRegistryError()) {
					sb.append("Severity: "
							+ (StringUtils.isNotBlank(re.getSeverity()) ? re.getSeverity().substring(
							re.getSeverity().lastIndexOf(":") + 1) : "?") + ", Code: "
							+ (StringUtils.isNotBlank(re.getErrorCode()) ? re.getErrorCode() : "?") + ", Message: "
							+ (StringUtils.isNotBlank(re.getCodeContext()) ? re.getCodeContext() : "?") + lf);
				}
			}

			throw new APIException(sb.toString());
		}
		return regResp.toString();
	}
}
