import { Button, Form, Input, Select, Space } from 'antd';
import type { DependencyField } from './GovernanceDependencyDialog';

/**
 * 已提取或待治理确认的指标执行口径。
 */
export type MetricRules = {
  /**
   * 是否必须绑定已确认的业务日期列。
   */
  dateMode?: 'NONE' | 'BUSINESS_DAY' | null;
  /**
   * 业务日期的稳定字段编号。
   */
  dateFieldId?: string | null;
  /**
   * 固定筛选的真实列身份和源库取值，空数组表示已核对无条件。
   */
  fixedFilters?: { fieldId?: string; value?: string }[] | null;
  /**
   * 计量单位取固定值或按实际来源列校验。
   */
  unitMode?: 'NONE' | 'FIXED' | 'COLUMN' | null;
  /**
   * 已声明的固定单位或币种。
   */
  unit?: string | null;
  /**
   * 币种或计量单位的文本列。
   */
  unitFieldId?: string | null;
};

/**
 * 显式区分未核对筛选、确认无筛选、以及已配置的固定字段值。
 */
export type FixedFilterMode = 'NONE' | 'FIELDS';

/**
 * 指标随现有候选表单提交，不另建办理步骤。
 */
export function MetricRulesFields({
  dependencies,
  disabled,
}: {
  dependencies: DependencyField[];
  disabled: boolean;
}) {
  const dateMode = Form.useWatch(['metricRules', 'dateMode']);
  const unitMode = Form.useWatch(['metricRules', 'unitMode']);
  const filterMode = Form.useWatch('fixedFilterMode');
  const options = dependencies.map((field) => ({
    value: field.target.targetId,
    label: field.label,
  }));
  return (
    <section aria-label="指标执行口径">
      {/* 仅当指标需要按日查询时，才选择对应的稳定依赖列。 */}
      <Form.Item label="业务日期" name={['metricRules', 'dateMode']}>
        <Select
          options={[
            { value: 'NONE', label: '不需要默认业务日期' },
            { value: 'BUSINESS_DAY', label: '按业务日期查询' },
          ]}
        />
      </Form.Item>
      {dateMode === 'BUSINESS_DAY' && (
        <Form.Item label="业务日期字段" name={['metricRules', 'dateFieldId']}>
          <Select options={options} />
        </Form.Item>
      )}

      {/* 明确核对有无固定筛选，避免把未提取的条件当成可忽略。 */}
      <Form.Item label="固定筛选" name="fixedFilterMode">
        <Select
          options={[
            { value: 'NONE', label: '无固定筛选' },
            { value: 'FIELDS', label: '配置固定筛选' },
          ]}
        />
      </Form.Item>
      {filterMode === 'FIELDS' && (
        <Form.List name={['metricRules', 'fixedFilters']}>
          {(fields, { add, remove }) => (
            <>
              {/* 逐行核对源字段及真实等值取值。 */}
              {fields.map(({ key, name }) => (
                <Space key={key} align="baseline" wrap>
                  <Form.Item label={`筛选字段 ${name + 1}`} name={[name, 'fieldId']}>
                    <Select options={options} style={{ minWidth: 180 }} />
                  </Form.Item>
                  <Form.Item label={`必须等于 ${name + 1}`} name={[name, 'value']}>
                    <Input maxLength={120} />
                  </Form.Item>
                  <Button disabled={disabled} onClick={() => remove(name)}>
                    移除
                  </Button>
                </Space>
              ))}
              <Form.Item>
                <Button disabled={disabled || fields.length >= 8} onClick={() => add()}>
                  添加条件
                </Button>
              </Form.Item>
            </>
          )}
        </Form.List>
      )}

      {/* 固定单位与逐行同币种校验是不同的确认选项。 */}
      <Form.Item label="单位或币种" name={['metricRules', 'unitMode']}>
        <Select
          options={[
            { value: 'NONE', label: '无单位' },
            { value: 'FIXED', label: '固定单位或币种' },
            { value: 'COLUMN', label: '按源列核对一致性' },
          ]}
        />
      </Form.Item>
      {unitMode === 'FIXED' && (
        <Form.Item label="统一单位" name={['metricRules', 'unit']}>
          <Input maxLength={32} />
        </Form.Item>
      )}
      {unitMode === 'COLUMN' && (
        <Form.Item label="单位或币种字段" name={['metricRules', 'unitFieldId']}>
          <Select options={options} />
        </Form.Item>
      )}
    </section>
  );
}
