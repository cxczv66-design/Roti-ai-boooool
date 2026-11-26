package com.example;

interface IEntityShellService {
    String execCommand(in String[] command);
    void destroy();
}
