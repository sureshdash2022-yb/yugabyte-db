/*
 * Copyright 2022 YugaByte, Inc. and Contributors
 * Licensed under the Polyform Free Trial License 1.0.0 (the "License")
 * You may not use this file except in compliance with the License. You may obtain a copy of the License at
 * http://github.com/YugaByte/yugabyte-db/blob/master/licenses/POLYFORM-FREE-TRIAL-LICENSE-1.0.0.txt
 */
import React from 'react';
import clsx from 'clsx';

import { YBButton } from '../../../redesign/components';

import styles from './EmptyListPlaceholder.module.scss';

interface EmptyListPlaceholderProps {
  actionButtonText: string;
  descriptionText: string;
  onActionButtonClick: () => void;

  className?: string;
}

const PLUS_ICON = <i className={`fa fa-plus ${styles.emptyIcon}`} />;

export const EmptyListPlaceholder = ({
  actionButtonText,
  className,
  descriptionText,
  onActionButtonClick
}: EmptyListPlaceholderProps) => (
  <div className={clsx(styles.emptyListContainer, className)}>
    {PLUS_ICON}
    <YBButton style={{ minWidth: '200px' }} variant="primary" onClick={onActionButtonClick}>
      <i className="fa fa-plus" />
      {actionButtonText}
    </YBButton>
    <div>{descriptionText}</div>
  </div>
);
