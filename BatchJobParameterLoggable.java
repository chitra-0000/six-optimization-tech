package com.bnpp.regliss.vo;

import com.fasterxml.jackson.annotation.JsonIgnore;

import java.io.Serializable;

public interface BatchJobParameterLoggable extends Serializable {

    @JsonIgnore
    String getPrettyValue();
}
