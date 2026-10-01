package com.bnpp.regliss.importer.six.mapper;

import org.springframework.stereotype.Service;

import java.util.List;

@Service
public class InstrumentFileMapper {
    public InstrumentFile mapperStructuredFile(InstrumentFilesDto dto) {

        InstrumentFile instrumentFile = new InstrumentFile();
        instrumentFile.setList(dto.getList());
        instrumentFile.setVersion(dto.getVersion());
        instrumentFile.setRecordOrigin(dto.getRecordOrigin());
        instrumentFile.setOlsYes(dto.getOlsYes());
        instrumentFile.setOlsNo(dto.getOlsNo());
        instrumentFile.setLinkEntity(dto.getLinkEntity());
        instrumentFile.setLinkCsid(dto.getLinkCsid());
        instrumentFile.setSanctionedParentEntity(dto.getSanctionedParentEntity());
        instrumentFile.setNameDirectIssuer(dto.getNameDirectIssuer());
        instrumentFile.setConfidenceLevel(dto.getConfidenceLevel());
        instrumentFile.setIsin(dto.getIsin());
        instrumentFile.setInstrName(dto.getInstrName());
        instrumentFile.setFisn(dto.getFisn());
        instrumentFile.setChValor(dto.getChValor());
        instrumentFile.setIndicativeIssueDate(dto.getIndicativeIssueDate());
        instrumentFile.setInstrumentType(dto.getInstrumentType());
        instrumentFile.setSanctionsRelevantAssetClass(dto.getSanctionsRelevantAssetClass());
        instrumentFile.setMainInstrument(dto.getMainInstrument());
        instrumentFile.setEquityTypeOfIssuance(dto.getEquityTypeOfIssuance());
        instrumentFile.setDenominationCurrency(dto.getDenominationCurrency());
        instrumentFile.setMaturityDate(dto.getMaturityDate());
        instrumentFile.setActiveFlag(dto.getActiveFlag());
        instrumentFile.setDebtLifetimeInDays(dto.getDebtLifetimeInDays());
        instrumentFile.setDateOpenedInSix(dto.getDateOpenedInSix());
        instrumentFile.setSubstituteIssueDate(dto.getSubstituteIssueDate());
        instrumentFile.setIssueDate(dto.getIssueDate());
        instrumentFile.setCapitalChangeDate(dto.getCapitalChangeDate());
        instrumentFile.setSedol(dto.getSedol());
        instrumentFile.setCusip(dto.getCusip());
        instrumentFile.setCins(dto.getCins());
        instrumentFile.setAustrian(dto.getAustrian());
        instrumentFile.setBelgian(dto.getBelgian());
        instrumentFile.setCanadian(dto.getCanadian());
        instrumentFile.setGerman(dto.getGerman());
        instrumentFile.setDenmark(dto.getDenmark());
        instrumentFile.setFranceRga(dto.getFranceRga());
        instrumentFile.setFranceEuroclear(dto.getFranceEuroclear());
        instrumentFile.setItalian(dto.getItalian());
        instrumentFile.setJapaneseCurrent(dto.getJapaneseCurrent());
        instrumentFile.setJapaneseNew(dto.getJapaneseNew());
        instrumentFile.setLuxembourg(dto.getLuxembourg());
        instrumentFile.setNetherland(dto.getNetherland());
        instrumentFile.setNorwegian(dto.getNorwegian());
        instrumentFile.setSwedish(dto.getSwedish());
        instrumentFile.setXsIntNumber(dto.getXsIntNumber());
        instrumentFile.setPortugal(dto.getPortugal());
        instrumentFile.setSouthKorea(dto.getSouthKorea());
        instrumentFile.setHongKong(dto.getHongKong());
        instrumentFile.setFigiGlobalId(dto.getFigiGlobalId());
        instrumentFile.setFigiGlobalShareClassLevelId(dto.getFigiGlobalShareClassLevelId());
        instrumentFile.setObligorRole(dto.getObligorRole());
        instrumentFile.setUnderlyingObligor(dto.getUnderlyingObligor());
        instrumentFile.setUnderlyingObligorGk(dto.getUnderlyingObligorGk());
        instrumentFile.setReferenceCapital(dto.getReferenceCapital());
        instrumentFile.setActualCapital(dto.getActualCapital());
        instrumentFile.setRegimes(dto.getRegimes());

        List<SixTargetDto> targetDtos = dto.getSixTargets();
        if (targetDtos != null) {
            for (SixTargetDto tDto : targetDtos) {
                SixTarget st = new SixTarget();
                st.setTarget(tDto.getTarget());
                st.setRegime(tDto.getRegime());
                st.setLegalBasis(tDto.getLegalBasis());
                st.setSanctioned(tDto.getSanctioned());
                st.setStatus(tDto.getStatus());
                st.setSanctionFlagChanged(tDto.getSanctionFlagChanged());
                st.setSanctionsRationale(tDto.getSanctionsRationale());
                st.setReasonForChange(tDto.getReasonForChange());

                instrumentFile.addSixTarget(st);
            }
        }

        return instrumentFile;
    }
}
