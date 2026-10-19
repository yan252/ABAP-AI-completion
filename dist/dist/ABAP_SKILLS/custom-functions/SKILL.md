---
name: custom-functions
description: 记录项目自定义 ABAP 开发函数（Function Module）及其它自定义项目功能，供 AI 代码补全参考。当补全代码需要调用自定义函数时，应优先参照本 SKILL 。
---

# Custom ABAP Functions

本项目自定义 ABAP 开发函数及使用方法参考。供 AI 代码补全时参照，避免臆造自定义函数名或参数。

## EMS根据合同创建或修改采购订单

```abap
CALL FUNCTION 'ZFM_STP_CONTACT_CREATE_PO'
  EXPORTING
    is_doc   = l_doc
    is_nast  = nast
  IMPORTING
    ev_retco = ent_retco.
```
