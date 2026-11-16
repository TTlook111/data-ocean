package com.dataocean.module.dashboard.entity.po;

/** 在独立反馈源范围内读取当前有效票；未评价回答仅计入 eligible。 */
public record QueryExperienceVotes(long eligible, long likes, long dislikes) {}
