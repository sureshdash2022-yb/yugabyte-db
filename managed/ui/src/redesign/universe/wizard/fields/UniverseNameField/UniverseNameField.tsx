import _ from 'lodash';
import React, { FC, useState } from 'react';
import { Controller, useFormContext } from 'react-hook-form';
import { YBInput } from '../../../../components';
import { api } from '../../../../helpers/api';
import { trimSpecialChars } from '../../../../../utils/ObjectUtils';
import { ErrorMessage } from '../../../../uikit/ErrorMessage/ErrorMessage';
import { useWhenMounted } from '../../../../helpers/hooks';
import { CloudConfigFormValue } from '../../steps/cloud/CloudConfig';
import YBLoadingCircleIcon from '../../../../../components/common/indicators/YBLoadingCircleIcon';
import './UniverseNameField.scss';

const ERROR_NAME_IN_USE = 'Universe already exists';
const ERROR_NAME_IS_REQUIRED = 'Universe name is required';
const ERROR_ILLEGAL_CHARS =
  'Universe name may contain letters, numbers, hyphen and slash symbols only';

interface UniverseNameFieldProps {
  disabled: boolean;
}

const FIELD_NAME = 'universeName';

export const UniverseNameField: FC<UniverseNameFieldProps> = ({ disabled }) => {
  const {
    control,
    formState: { errors }
  } = useFormContext<CloudConfigFormValue>();
  const [isValidating, setIsValidating] = useState(false);
  const whenMounted = useWhenMounted();

  // return "true" when value is valid and validation error message otherwise
  const validate = async (value: string): Promise<boolean | string> => {
    if (disabled) return true; // don't validate disabled field
    if (!value) return ERROR_NAME_IS_REQUIRED;
    if (value !== trimSpecialChars(value)) return ERROR_ILLEGAL_CHARS;

    let errorMessage = '';
    try {
      setIsValidating(true);
      await api.findUniverseByName(value);
      whenMounted(() => setIsValidating(false));
    } catch (error: any) {
      // skip exceptions happened due to canceling previous request
      if (!api.isRequestCancelError(error)) {
        // empty "error.response" usually means network error, so show default message from browser
        errorMessage = _.get(error, 'response.data.error', ERROR_NAME_IN_USE);

        whenMounted(() => setIsValidating(false));
      }
    }

    return errorMessage?.length ? errorMessage : true;
  };

  return (
    <div className="universe-name-field">
      <Controller
        control={control}
        name={FIELD_NAME}
        rules={{ validate }}
        render={({ field: { onChange, value } }) => <YBInput onChange={onChange} value={value} />}
      />
      {isValidating && (
        <div className="universe-name-field__validation-spinner">
          <YBLoadingCircleIcon size="small" />
        </div>
      )}
      <ErrorMessage message={errors[FIELD_NAME]?.message} show={!isValidating} />
    </div>
  );
};
