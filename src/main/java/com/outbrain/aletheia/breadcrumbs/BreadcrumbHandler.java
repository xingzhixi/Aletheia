package com.outbrain.aletheia.breadcrumbs;

import com.outbrain.aletheia.breadcrumbs.Breadcrumb;

/**
 * Created by slevin on 8/11/14.
 */
public interface BreadcrumbHandler {
  void handle(final Breadcrumb breadcrumb);
}