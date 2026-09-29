package com.bnpp.regliss.importer.six.mapper;

import org.springframework.stereotype.Service;

import java.util.List;

// TODO: the project imports were collapsed ("import ...") in the screenshots.
// Re-add them in the IDE (Alt+Enter / Optimize Imports) for:
// InstrumentFile, StructuredFile, OptionsFile, SixTarget,
// FilteredInstrumentFile, FilteredStructuredFile, FilteredOptionsFile, FilteredSixTarget

@Service
public class FilteredDataMapper {
    public FilteredInstrumentFile filteredInstrumentFile(InstrumentFile instrumentFile, String reference) {

        FilteredInstrumentFile filteredInstrumentFile = new FilteredInstrumentFile();
        filteredInstrumentFile.setListRef(reference);
        filteredInstrumentFile.setVersion(instrumentFile.getVersion());
        filteredInstrumentFile.setLinkEntity(instrumentFile.getLinkEntity());
        filteredInstrumentFile.setLinkCsid(instrumentFile.getLinkCsid());
        filteredInstrumentFile.setSanctionedParentEntity(instrumentFile.getSanctionedParentEntity());
        filteredInstrumentFile.setNameDirectIssuer(instrumentFile.getNameDirectIssuer());
        filteredInstrumentFile.setConfidenceLevel(instrumentFile.getConfidenceLevel());
        filteredInstrumentFile.setIsin(instrumentFile.getIsin());
        filteredInstrumentFile.setInstrName(instrumentFile.getInstrName());
        filteredInstrumentFile.setFisn(instrumentFile.getFisn());
        filteredInstrumentFile.setChValor(instrumentFile.getChValor());
        filteredInstrumentFile.setIndicativeIssueDate(instrumentFile.getIndicativeIssueDate());
        filteredInstrumentFile.setInstrumentType(instrumentFile.getInstrumentType());
        filteredInstrumentFile.setSanctionsRelevantAssetClass(instrumentFile.getSanctionsRelevantAssetClass());
        filteredInstrumentFile.setMainInstrument(instrumentFile.getMainInstrument());
        filteredInstrumentFile.setEquityTypeOfIssuance(instrumentFile.getEquityTypeOfIssuance());
        filteredInstrumentFile.setDenominationCurrency(instrumentFile.getDenominationCurrency());
        filteredInstrumentFile.setMaturityDate(instrumentFile.getMaturityDate());
        filteredInstrumentFile.setActiveFlag(instrumentFile.getActiveFlag());
        filteredInstrumentFile.setDebtLifetimeInDays(instrumentFile.getDebtLifetimeInDays());
        filteredInstrumentFile.setIssueDate(instrumentFile.getIssueDate());
        filteredInstrumentFile.setCapitalChangeDate(instrumentFile.getCapitalChangeDate());
        filteredInstrumentFile.setSedol(instrumentFile.getSedol());
        filteredInstrumentFile.setCusip(instrumentFile.getCusip());
        filteredInstrumentFile.setCins(instrumentFile.getCins());
        filteredInstrumentFile.setAustrian(instrumentFile.getAustrian());
        filteredInstrumentFile.setBelgian(instrumentFile.getBelgian());
        filteredInstrumentFile.setCanadian(instrumentFile.getCanadian());
        filteredInstrumentFile.setGerman(instrumentFile.getGerman());
        filteredInstrumentFile.setDenmark(instrumentFile.getDenmark());
        filteredInstrumentFile.setFranceRga(instrumentFile.getFranceRga());
        filteredInstrumentFile.setFranceEuroclear(instrumentFile.getFranceEuroclear());
        filteredInstrumentFile.setItalian(instrumentFile.getItalian());
        filteredInstrumentFile.setJapaneseCurrent(instrumentFile.getJapaneseCurrent());
        filteredInstrumentFile.setJapaneseNew(instrumentFile.getJapaneseNew());
        filteredInstrumentFile.setLuxembourg(instrumentFile.getLuxembourg());
        filteredInstrumentFile.setNetherland(instrumentFile.getNetherland());
        filteredInstrumentFile.setNorwegian(instrumentFile.getNorwegian());
        filteredInstrumentFile.setSwedish(instrumentFile.getSwedish());
        filteredInstrumentFile.setXsIntNumber(instrumentFile.getXsIntNumber());
        filteredInstrumentFile.setPortugal(instrumentFile.getPortugal());
        filteredInstrumentFile.setSouthKorea(instrumentFile.getSouthKorea());
        filteredInstrumentFile.setHongKong(instrumentFile.getHongKong());
        filteredInstrumentFile.setFigiGlobalId(instrumentFile.getFigiGlobalId());

        List<SixTarget> targetDtos = instrumentFile.getSixTargets();
        if (targetDtos != null) {
            for (SixTarget tDto : targetDtos) {
                FilteredSixTarget st = new FilteredSixTarget();
                st.setTarget(tDto.getTarget());
                st.setRegime(tDto.getRegime());
                st.setLegalBasis(tDto.getLegalBasis());
                st.setSanctioned(tDto.getSanctioned());
                st.setSanctionsRationale(tDto.getSanctionsRationale());
                st.setReasonForChange(tDto.getReasonForChange());

                filteredInstrumentFile.addSixTarget(st);
            }
        }

        return filteredInstrumentFile;
    }

    public FilteredStructuredFile filteredStructuredFile(StructuredFile structuredFile, String reference) {
        FilteredStructuredFile entity = new FilteredStructuredFile();
        entity.setListRef(reference);
        entity.setVersion(structuredFile.getVersion());
        entity.setHostCh(structuredFile.getHostCh());
        entity.setHostIsin(structuredFile.getHostIsin());
        entity.setHostGk(structuredFile.getHostGk());
        entity.setHostIssuerShortname(structuredFile.getHostIssuerShortname());
        entity.setDescription(structuredFile.getDescription());
        entity.setFisn(structuredFile.getFisn());
        entity.setIndicativeIssueDate(structuredFile.getIndicativeIssueDate());
        entity.setIssueDate(structuredFile.getIssueDate());
        entity.setInstrumentType(structuredFile.getInstrumentType());
        entity.setDenominationCurrency(structuredFile.getDenominationCurrency());
        entity.setMaturityDate(structuredFile.getMaturityDate());
        entity.setActiveFlag(structuredFile.getActiveFlag());
        entity.setUnderlyingCh(structuredFile.getUnderlyingCh());
        entity.setUnderlyingIsin(structuredFile.getUnderlyingIsin());
        entity.setUnderlyingGk(structuredFile.getUnderlyingGk());
        entity.setUnderlyingIssuerShortname(structuredFile.getUnderlyingIssuerShortname());
        entity.setSedol(structuredFile.getSedol());
        entity.setCusip(structuredFile.getCusip());
        entity.setCins(structuredFile.getCins());
        entity.setFigiGlobalId(structuredFile.getFigiGlobalId());
        entity.setAustrian(structuredFile.getAustrian());
        entity.setBelgian(structuredFile.getBelgian());
        entity.setCanadian(structuredFile.getCanadian());
        entity.setGerman(structuredFile.getGerman());
        entity.setDenmark(structuredFile.getDenmark());
        entity.setFranceRga(structuredFile.getFranceRga());
        entity.setFranceEuroClear(structuredFile.getFranceEuroClear());
        entity.setItalian(structuredFile.getItalian());
        entity.setJapaneseCurrent(structuredFile.getJapaneseCurrent());
        entity.setJapaneseNew(structuredFile.getJapaneseNew());
        entity.setLuxembourg(structuredFile.getLuxembourg());
        entity.setNetherland(structuredFile.getNetherland());
        entity.setNorwegian(structuredFile.getNorwegian());
        entity.setSwedish(structuredFile.getSwedish());
        entity.setXsIntNumber(structuredFile.getXsIntNumber());
        entity.setPortugal(structuredFile.getPortugal());
        entity.setSouthKorea(structuredFile.getSouthKorea());
        entity.setHongKong(structuredFile.getHongKong());
        entity.setConfidenceLevel(structuredFile.getConfidenceLevel());

        List<SixTarget> targetDtos = structuredFile.getSixTargets();
        if (targetDtos != null) {
            for (SixTarget tDto : targetDtos) {
                FilteredSixTarget st = new FilteredSixTarget();
                st.setTarget(tDto.getTarget());
                st.setRegime(tDto.getRegime());
                st.setLegalBasis(tDto.getLegalBasis());
                st.setSanctioned(tDto.getSanctioned());
                st.setReasonForChange(tDto.getReasonForChange());

                entity.addSixTarget(st);
            }
        }
        return entity;
    }

    public FilteredOptionsFile filteredOptionsFile(OptionsFile optionsFile, String reference) {
        FilteredOptionsFile filteredOptions = new FilteredOptionsFile();
        filteredOptions.setList(optionsFile.getList());
        filteredOptions.setListRef(reference);
        filteredOptions.setVersion(optionsFile.getVersion());
        filteredOptions.setChOption(optionsFile.getChOption());
        filteredOptions.setIsinOption(optionsFile.getIsinOption());
        filteredOptions.setDescription(optionsFile.getDescription());
        filteredOptions.setFisn(optionsFile.getFisn());
        filteredOptions.setIssuerGk(optionsFile.getIssuerGk());
        filteredOptions.setIssuerName(optionsFile.getIssuerName());
        filteredOptions.setDateOpenedInSix(optionsFile.getDateOpenedInSix());
        filteredOptions.setExpiryDate(optionsFile.getExpiryDate());
        filteredOptions.setInstrumentType(optionsFile.getInstrumentType());
        filteredOptions.setDenominationCurrency(optionsFile.getDenominationCurrency());
        filteredOptions.setActiveFlag(optionsFile.getActiveFlag());
        filteredOptions.setIssueDate(optionsFile.getIssueDate());
        filteredOptions.setUnderlyingCh(optionsFile.getUnderlyingCh());
        filteredOptions.setUnderlyingIsin(optionsFile.getUnderlyingIsin());
        filteredOptions.setUnderlyingIssuerGk(optionsFile.getUnderlyingIssuerGk());
        filteredOptions.setUnderlyingIssuerName(optionsFile.getUnderlyingIssuerName());
        filteredOptions.setSedol(optionsFile.getSedol());
        filteredOptions.setCusip(optionsFile.getCusip());
        filteredOptions.setCins(optionsFile.getCins());
        filteredOptions.setAustrian(optionsFile.getAustrian());
        filteredOptions.setBelgian(optionsFile.getBelgian());
        filteredOptions.setCanadian(optionsFile.getCanadian());
        filteredOptions.setGerman(optionsFile.getGerman());
        filteredOptions.setDenmark(optionsFile.getDenmark());
        filteredOptions.setFranceRga(optionsFile.getFranceRga());
        filteredOptions.setFranceEuroclear(optionsFile.getFranceEuroclear());
        filteredOptions.setItalian(optionsFile.getItalian());
        filteredOptions.setJapaneseCurrent(optionsFile.getJapaneseCurrent());
        filteredOptions.setJapaneseNew(optionsFile.getJapaneseNew());
        filteredOptions.setLuxembourg(optionsFile.getLuxembourg());
        filteredOptions.setNetherland(optionsFile.getNetherland());
        filteredOptions.setNorwegian(optionsFile.getNorwegian());
        filteredOptions.setSwedish(optionsFile.getSwedish());
        filteredOptions.setXsIntNumber(optionsFile.getXsIntNumber());
        filteredOptions.setPortugal(optionsFile.getPortugal());
        filteredOptions.setSouthKorea(optionsFile.getSouthKorea());
        filteredOptions.setHongKong(optionsFile.getHongKong());
        filteredOptions.setFigiGlobalId(optionsFile.getFigiGlobalId());
        filteredOptions.setFigiGlobalShareClassLevelId(optionsFile.getFigiGlobalShareClassLevelId());
        filteredOptions.setRegimes(optionsFile.getRegimes());
        filteredOptions.setConfidenceLevel(optionsFile.getConfidenceLevel());

        List<SixTarget> targetDtos = optionsFile.getSixTargets();
        if (targetDtos != null) {
            for (SixTarget tDto : targetDtos) {
                FilteredSixTarget st = new FilteredSixTarget();
                st.setTarget(tDto.getTarget());
                st.setRegime(tDto.getRegime());
                st.setLegalBasis(tDto.getLegalBasis());
                st.setSanctioned(tDto.getSanctioned());
                st.setReasonForChange(tDto.getReasonForChange());

                filteredOptions.addSixTarget(st);
            }
        }

        return filteredOptions;
    }
}
