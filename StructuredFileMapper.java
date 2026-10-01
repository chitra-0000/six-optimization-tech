package com.bnpp.regliss.importer.six.mapper;

import org.springframework.stereotype.Service;

import java.util.List;

@Service
public class StructuredFileMapper {

    public StructuredFile mapperStructuredFile(StructuredFileDto dto) {
        StructuredFile entity = new StructuredFile();
        entity.setList(dto.getList());
        entity.setVersion(dto.getVersion());
        entity.setHostCh(dto.getHostCh());
        entity.setHostIsin(dto.getHostIsin());
        entity.setHostGk(dto.getHostGk());
        entity.setHostIssuerShortname(dto.getHostIssuerShortname());
        entity.setDescription(dto.getDescription());
        entity.setFisn(dto.getFisn());
        entity.setDateOpenedInSix(dto.getDateOpenedInSix());
        entity.setSubstituteIssueDate(dto.getSubstituteIssueDate());
        entity.setIndicativeIssueDate(dto.getIndicativeIssueDate());
        entity.setIssueDate(dto.getIssueDate());
        entity.setSettlementStyle(dto.getSettlementStyle());
        entity.setInstrumentType(dto.getInstrumentType());
        entity.setDenominationCurrency(dto.getDenominationCurrency());
        entity.setMaturityDate(dto.getMaturityDate());
        entity.setActiveFlag(dto.getActiveFlag());
        entity.setUnderlyingCh(dto.getUnderlyingCh());
        entity.setUnderlyingIsin(dto.getUnderlyingIsin());
        entity.setUnderlyingGk(dto.getUnderlyingGk());
        entity.setUnderlyingIssuerShortname(dto.getUnderlyingIssuerShortname());
        entity.setSedol(dto.getSedol());
        entity.setCusip(dto.getCusip());
        entity.setCins(dto.getCins());
        entity.setFigiGlobalId(dto.getFigiGlobalId());
        entity.setFigiGlobalShareClassLevelId(dto.getFigiGlobalShareClassLevelId());
        entity.setAustrian(dto.getAustrian());
        entity.setBelgian(dto.getBelgian());
        entity.setCanadian(dto.getCanadian());
        entity.setGerman(dto.getGerman());
        entity.setDenmark(dto.getDenmark());
        entity.setFranceRga(dto.getFranceRga());
        entity.setFranceEuroClear(dto.getFranceEuroClear());
        entity.setItalian(dto.getItalian());
        entity.setJapaneseCurrent(dto.getJapaneseCurrent());
        entity.setJapaneseNew(dto.getJapaneseNew());
        entity.setLuxembourg(dto.getLuxembourg());
        entity.setNetherland(dto.getNetherland());
        entity.setNorwegian(dto.getNorwegian());
        entity.setSwedish(dto.getSwedish());
        entity.setXsIntNumber(dto.getXsIntNumber());
        entity.setPortugal(dto.getPortugal());
        entity.setSouthKorea(dto.getSouthKorea());
        entity.setHongKong(dto.getHongKong());
        entity.setRegimes(dto.getRegimes());
        entity.setConfidenceLevel(dto.getConfidenceLevel());

        List<SixTargetDto> targetDtos = dto.getTargets();
        if (targetDtos != null) {
            for (SixTargetDto tDto : targetDtos) {
                SixTarget st = new SixTarget();
                st.setTarget(tDto.getTarget());
                st.setRegime(tDto.getRegime());
                st.setLegalBasis(tDto.getLegalBasis());
                st.setSanctioned(tDto.getSanctioned());
                st.setStatus(tDto.getStatus());
                st.setReasonForChange(tDto.getReasonForChange());

                entity.addSixTarget(st);
            }
        }
        return entity;
    }
}
