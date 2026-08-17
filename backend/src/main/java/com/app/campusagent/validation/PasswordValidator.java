package com.app.campusagent.validation;

import com.app.campusagent.util.PasswordRules;
import jakarta.validation.ConstraintValidator;
import jakarta.validation.ConstraintValidatorContext;

/** {@link ValidPassword} 的校验器，直接复用 {@link PasswordRules}。 */
public class PasswordValidator implements ConstraintValidator<ValidPassword, String> {

    @Override
    public boolean isValid(String value, ConstraintValidatorContext context) {
        return PasswordRules.isValidLength(value);
    }
}
