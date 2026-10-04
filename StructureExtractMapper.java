package com.bnpp.regliss.importer.six.extractor;

import org.springframework.jdbc.core.RowMapper;

import java.sql.ResultSet;
import java.sql.SQLException;

public class StructureExtractMapper implements RowMapper<ExtractDto> {

    @Override
    public ExtractDto mapRow(ResultSet rs, int rowNum) throws SQLException {
        return new ExtractDto(
                rs.getLong("i_id"),
                rs.getString("i_host_isin")
        );
    }
}
