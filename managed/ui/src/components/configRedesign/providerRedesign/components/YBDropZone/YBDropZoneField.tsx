/*
 * Copyright 2023 YugaByte, Inc. and Contributors
 * Licensed under the Polyform Free Trial License 1.0.0 (the "License")
 * You may not use this file except in compliance with the License. You may obtain a copy of the License at
 * http://github.com/YugaByte/yugabyte-db/blob/master/licenses/POLYFORM-FREE-TRIAL-LICENSE-1.0.0.txt
 */

import React from 'react';
import { Box, FormHelperText } from '@material-ui/core';
import clsx from 'clsx';
import { FieldValues, useController, UseControllerProps } from 'react-hook-form';

import { YBDropZone, YBDropZoneProps } from './YBDropZone';

import styles from './YBDropZoneField.module.scss';

type YBDropZoneFieldProps<T extends FieldValues> = YBDropZoneProps & UseControllerProps<T>;

export const YBDropZoneField = <T extends FieldValues>({
  actionButtonText,
  className,
  descriptionText,
  dragOverText,
  multipleFiles = true,
  showHelpText = true,
  ...useControllerProps
}: YBDropZoneFieldProps<T>) => {
  const { field, fieldState } = useController(useControllerProps);

  return (
    <Box width="100%">
      <YBDropZone
        value={field.value}
        actionButtonText={actionButtonText}
        className={clsx(className, fieldState.error && styles.emptyListError)}
        descriptionText={descriptionText}
        dragOverText={dragOverText}
        multipleFiles={multipleFiles}
        onChange={field.onChange}
        showHelpText={showHelpText}
        dataTestId={`YBDropZoneField-${useControllerProps.name}`}
      />
      {fieldState.error?.message && (
        <FormHelperText error={true}>{fieldState.error?.message}</FormHelperText>
      )}
    </Box>
  );
};
