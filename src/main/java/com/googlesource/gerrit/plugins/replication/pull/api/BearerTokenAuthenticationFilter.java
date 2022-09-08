package com.googlesource.gerrit.plugins.replication.pull.api;

import com.google.gerrit.httpd.AllRequestFilter;

import javax.servlet.FilterChain;
import javax.servlet.ServletException;
import javax.servlet.ServletRequest;
import javax.servlet.ServletResponse;
import java.io.IOException;

public class BearerTokenAuthenticationFilter extends AllRequestFilter {

  public void BearerTokenAuthenticationFilter(){}

  @Override
  public void doFilter(ServletRequest servletRequest, ServletResponse servletResponse, FilterChain filterChain) throws IOException, ServletException {

  }
}
