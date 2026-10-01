package com.bnpp.regliss.importer.six.mapper;

import org.springframework.stereotype.Service;

import java.util.List;

@Service
public class OptionsFileMapper {

    public OptionsFile mapperStructuredFile(OptionsFileDto dto) {
        OptionsFile optionsFile = new OptionsFile();
        optionsFile.setList(dto.getList());
        optionsFile.setVersion(dto.getVersion());
        optionsFile.setChOption(dto.getChOption());
        optionsFile.setIsinOption(dto.getIsinOption());
        optionsFile.setDescription(dto.getDescription());
        optionsFile.setFisn(dto.getFisn());
        optionsFile.setIssuerGk(dto.getIssuerGk());
        optionsFile.setIssuerName(dto.getIssuerName());
        optionsFile.setDateOpenedInSix(dto.getDateOpenedInSix());
        optionsFile.setExpiryDate(dto.getExpiryDate());
        optionsFile.setInstrumentType(dto.getInstrumentType());
        optionsFile.setDenominationCurrency(dto.getDenominationCurrency());
        optionsFile.setActiveFlag(dto.getActiveFlag());
        optionsFile.setIssueDate(dto.getIssueDate());
        optionsFile.setUnderlyingCh(dto.getUnderlyingCh());
        optionsFile.setUnderlyingIsin(dto.getUnderlyingIsin());
        optionsFile.setUnderlyingIssuerGk(dto.getUnderlyingIssuerGk());
        optionsFile.setUnderlyingIssuerName(dto.getUnderlyingIssuerName());
        optionsFile.setSedol(dto.getSedol());
        optionsFile.setCusip(dto.getCusip());
        optionsFile.setCins(dto.getCins());
        optionsFile.setAustrian(dto.getAustrian());
        optionsFile.setBelgian(dto.getBelgian());
        optionsFile.setCanadian(dto.getCanadian());
        optionsFile.setGerman(dto.getGerman());
        optionsFile.setDenmark(dto.getDenmark());
        optionsFile.setFranceRga(dto.getFranceRga());
        optionsFile.setFranceEuroclear(dto.getFranceEuroclear());
        optionsFile.setItalian(dto.getItalian());
        optionsFile.setJapaneseCurrent(dto.getJapaneseCurrent());
        optionsFile.setJapaneseNew(dto.getJapaneseNew());
        optionsFile.setLuxembourg(dto.getLuxembourg());
        optionsFile.setNetherland(dto.getNetherland());
        optionsFile.setNorwegian(dto.getNorwegian());
        optionsFile.setSwedish(dto.getSwedish());
        optionsFile.setXsIntNumber(dto.getXsIntNumber());
        optionsFile.setPortugal(dto.getPortugal());
        optionsFile.setSouthKorea(dto.getSouthKorea());
        optionsFile.setHongKong(dto.getHongKong());
        optionsFile.setFigiGlobalId(dto.getFigiGlobalId());
        optionsFile.setFigiGlobalShareClassLevelId(dto.getFigiGlobalShareClassLevelId());
        optionsFile.setConfidenceLevel(dto.getConfidenceLevel());

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

                optionsFile.addSixTarget(st);
            }
        }
        return optionsFile;
    }
}
